"""Adversarial checks on the disposable M11 stack. Never print signed URLs."""
import http.client
import socket
import time
from urllib.parse import urlsplit
import uuid


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def check_hardening(admin, upload_path, movie, upload_id, grant, clip, sql):
    uri = urlsplit(grant["url"])
    require(uri.scheme == "http" and uri.hostname == "127.0.0.1", "Expected loopback capability")
    target = uri.path + "?" + uri.query

    def request(method="PUT", path=target, headers=None, body=clip):
        connection = http.client.HTTPConnection(uri.hostname, uri.port, timeout=10)
        try:
            connection.request(method, path, body=body, headers=headers or grant["requiredHeaders"])
            response = connection.getresponse()
            response.read(65536)
            return response.status
        finally:
            connection.close()

    for path in (target.replace("/staging/", "/sources/"),
                 target.replace("/libra-source/staging/", "/libra-hls/hls/"),
                 target.replace(upload_id, str(uuid.uuid4()))):
        require(request(path=path) == 403, "Changed object capability was accepted")
    for method in ("GET", "HEAD", "DELETE"):
        require(request(method=method) == 403, "Changed method was accepted")
    require(request(method="GET", path="/libra-source?list-type=2", body=None) == 403,
            "Anonymous listing was allowed")
    for name in grant["requiredHeaders"]:
        headers = dict(grant["requiredHeaders"])
        headers.pop(name)
        require(request(headers=headers) == 403, "Missing signed header was accepted")
    require(request(body=clip + b"x") == 403, "Declared length was not enforced")
    require(request(body=clip[:-1]) == 403, "Short body was accepted")
    require(request(body=bytes(len(clip))) == 400, "Mismatched payload checksum was accepted")
    require(request() == 200 and request() == 200, "Identical staging replay must remain possible")

    def start_upload(length=len(clip), expect=False):
        connection = socket.create_connection((uri.hostname, uri.port), timeout=10)
        connection.settimeout(40)
        lines = [f"PUT {target} HTTP/1.1", f"Host: {uri.netloc}",
                 f"Content-Length: {length}", "Connection: close"]
        lines.extend(f"{key}: {value}" for key, value in grant["requiredHeaders"].items())
        if expect:
            lines.append("Expect: 100-continue")
        connection.sendall(("\r\n".join(lines) + "\r\n\r\n").encode("ascii"))
        return connection

    with start_upload(268435457, expect=True) as connection:
        require(connection.recv(4096).startswith(b"HTTP/1.1 413"), "Gateway byte ceiling failed")

    connections = []
    try:
        # Leave eight valid requests waiting for their remaining bytes.
        for _ in range(8):
            connection = start_upload()
            connections.append(connection)
            connection.sendall(clip[:1])
        time.sleep(0.5)
        with start_upload() as rejected:
            rejected.sendall(clip[:1])
            require(rejected.recv(4096).startswith(b"HTTP/1.1 429"), "Concurrent upload bound failed")
    finally:
        for connection in connections:
            connection.close()
    time.sleep(1)
    with start_upload() as idle:
        idle.sendall(clip[:1])
        started = time.monotonic()
        response = idle.recv(4096)
        # With unbuffered proxying Nginx may close the socket without an HTTP
        # response. Require EOF/timeout status and the measured 30s bound.
        elapsed = time.monotonic() - started
        status = response.split(b" ", 2)[1] if b" " in response else (b"closed" if not response else b"invalid")
        require(status in (b"408", b"504", b"closed") and 25 <= elapsed < 38,
                f"Idle request bound failed: status={status.decode('ascii')}, seconds={elapsed:.1f}")

    for size in (0, 268435457):
        admin.request("POST", upload_path, status=400, body={
            "requestId": str(uuid.uuid4()), "expectedVersion": movie["version"],
            "byteLength": size, "sha256": "a" * 64})
    path = f"/v1/admin/uploads/{upload_id}/upload-url"
    job_id = str(uuid.uuid4())
    # Seed a coherent completed-submission snapshot, including its existing job.
    sql("libra_media", f"BEGIN; UPDATE media_uploads SET state='SUBMITTED' WHERE id='{upload_id}'; "
        f"UPDATE media_assets SET state='QUEUED' WHERE binding_id="
        f"(SELECT binding_id FROM media_uploads WHERE id='{upload_id}'); "
        f"INSERT INTO media_jobs(id,upload_id,next_attempt_at,created_at,updated_at) "
        f"VALUES ('{job_id}','{upload_id}',now(),now(),now()); COMMIT")
    admin.request("POST", path, status=409)
    require(sql("libra_media", "SELECT id FROM media_jobs") == job_id,
            "Denied grant changed the submitted job fixture")
    # Fault fixture only: exercise expiry without waiting an hour or implementing completion.
    sql("libra_media", f"BEGIN; DELETE FROM media_jobs WHERE id='{job_id}'; "
        f"UPDATE media_assets SET state='UPLOADING' WHERE binding_id="
        f"(SELECT binding_id FROM media_uploads WHERE id='{upload_id}'); "
        f"UPDATE media_uploads SET state='OPEN', created_at=now()-interval '2 hours', "
        f"expires_at=now()-interval '1 hour' WHERE id='{upload_id}'; COMMIT")
    # Align the Core fixture's saved deadline so the cross-service identity check is still real.
    deadline = sql("libra_media", f"SELECT expires_at FROM media_uploads WHERE id='{upload_id}'")
    sql("libra_core", f"UPDATE catalog_upload_intents SET created_at='{deadline}'::timestamptz-interval '1 hour', "
        f"expires_at='{deadline}' WHERE id='{upload_id}'")
    admin.request("POST", path, status=410)
    require(sql("libra_media", "SELECT count(*) FROM media_jobs") == "0", "Invalid upload entered processing")
    require(sql("libra_media", "SELECT count(*) FROM media_outbox_events") == "0", "Unexpected publication event")
    return ["Browser denied foreign origin and tampered Content-Type",
            "Direct HTTP denied path/method/header/length/checksum tampering and anonymous list",
            "Identical signed PUT replay remained confined to staging",
            "Gateway rejected >256 MiB headers and ninth concurrent upload",
            f"Idle upload terminated: {status.decode('ascii')} after {elapsed:.1f}s",
            "Core rejected invalid sizes and grants after submitted/expired session fixtures",
            "No processing jobs or asset events from adversarial requests"]
