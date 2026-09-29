"""M14 durable completion: real services, PostgreSQL, SeaweedFS and lost response."""
import argparse
from datetime import datetime, timezone
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import threading
import urllib.request
import uuid

from media_m11_http import DirectCoreBrowser, require, wait_health

ROOT = Path(__file__).resolve().parents[2]


class CompletionProxy(ThreadingHTTPServer):
    def __init__(self, port, media_port):
        super().__init__(("127.0.0.1", port), CompletionHandler)
        self.media_port = media_port
        self.drop_response = threading.Event()
        self.completions = []
        self.lock = threading.Lock()


class CompletionHandler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_GET(self):
        self.forward()

    def do_PUT(self):
        self.forward()

    def do_POST(self):
        self.forward()

    def forward(self):
        length = int(self.headers.get("Content-Length", "0"))
        require(0 <= length <= 65536, "Unexpected request size")
        body = self.rfile.read(length) if length else None
        connection = http.client.HTTPConnection("127.0.0.1", self.server.media_port, timeout=10)
        try:
            connection.request(self.command, self.path, body=body, headers={
                "Authorization": self.headers.get("Authorization", ""),
                "Content-Type": self.headers.get("Content-Type", "application/json")})
            response = connection.getresponse()
            data = response.read(65537)
            require(len(data) <= 65536, "Unexpected response size")
            if self.command == "POST" and self.path.endswith("/complete"):
                dropped = self.server.drop_response.is_set()
                with self.server.lock:
                    self.server.completions.append((response.status, json.loads(data), dropped))
                if dropped:
                    # Receiving 202 from Media happens after its transaction committed.
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                    return
            self.send_response(response.status)
            self.send_header("Content-Type", response.getheader("Content-Type", "application/json"))
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
        finally:
            connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("core-url", "media-url", "project", "compose-file"):
        parser.add_argument("--" + name, required=True)
    parser.add_argument("--proxy-port", type=int, required=True)
    args = parser.parse_args()
    require(re.fullmatch(r"libra-m11-[a-f0-9]{12}", args.project), "Expected isolated project")
    compose_file = Path(args.compose_file).resolve()
    require(compose_file == ROOT / "infra/smoke/compose.media-m11.yaml", "Unexpected Compose file")
    for value in (args.core_url, args.media_url):
        require(re.fullmatch(r"http://127\.0\.0\.1:[0-9]+", value), "Services must bind loopback")
    require(1 <= args.proxy_port <= 65535, "Invalid proxy port")
    compose = ["docker", "compose", "--project-name", args.project, "--file", str(compose_file)]
    evidence = ROOT / "target/verification/media-m14.json"
    evidence.unlink(missing_ok=True)

    def sql(query):
        result = subprocess.run([*compose, "exec", "-T", "postgres", "psql", "-U", "postgres",
                                 "-d", "libra_media", "-At", "-v", "ON_ERROR_STOP=1", "-c", query],
                                capture_output=True, text=True, timeout=20)
        require(result.returncode == 0, "Disposable database query failed")
        return result.stdout.strip()

    proxy = CompletionProxy(args.proxy_port, int(args.media_url.rsplit(":", 1)[1]))
    thread = threading.Thread(target=proxy.serve_forever, daemon=True)
    thread.start()
    try:
        wait_health(args.core_url)
        wait_health(args.media_url)
        admin = DirectCoreBrowser(args.core_url)
        admin.request("POST", "/v1/auth/login", body={
            "email": "admin@m11.example.test", "password": os.environ["LIBRA_M11_ADMIN_PASSWORD"]})
        movie, _ = admin.request("POST", "/v1/admin/catalog", status=201, body={
            "kind": "MOVIE", "metadata": {"title": "M14 completion fault fixture",
            "description": "Disposable completion hardening", "genres": ["drama"], "releaseYear": 2026,
            "language": "en", "credits": [], "imageReferences": [], "accessTier": "FREE"}})
        clip = (ROOT / "infra/smoke/fixtures/m11-test-clip.mp4").read_bytes()
        created, _ = admin.request("POST", f"/v1/admin/catalog/{movie['id']}/uploads", status=201, body={
            "requestId": str(uuid.uuid4()), "expectedVersion": movie["version"],
            "byteLength": len(clip), "sha256": hashlib.sha256(clip).hexdigest()})
        upload_id = str(uuid.UUID(created["uploadId"]))
        path = f"/v1/admin/uploads/{upload_id}"
        missing, _ = admin.request("POST", path + "/complete", status=409)
        require(missing["code"] == "SOURCE_MISSING", "Absent staging was not rejected")
        require(sql("SELECT count(*) FROM media_jobs") == "0", "Missing source allocated a job")
        grant, _ = admin.request("POST", path + "/upload-url")
        require(grant["url"].startswith(os.environ["MEDIA_S3_BROWSER_ENDPOINT"] + "/libra-source/staging/"),
                "Unexpected signed destination")

        def signed_put():
            request = urllib.request.Request(grant["url"], method="PUT", data=clip,
                                             headers=grant["requiredHeaders"])
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=10) as response:
                require(response.status == 200, "Signed staging PUT failed")

        signed_put()
        sql("""CREATE FUNCTION reject_m14_job() RETURNS trigger LANGUAGE plpgsql AS $$
               BEGIN RAISE EXCEPTION 'private database failure fixture'; END $$;
               CREATE TRIGGER reject_m14_job BEFORE INSERT ON media_jobs
               FOR EACH ROW EXECUTE FUNCTION reject_m14_job();""")
        try:
            failed, _ = admin.request("POST", path + "/complete", status=503)
            require(failed["code"] == "MEDIA_UNAVAILABLE", "Database failure code changed")
            require("private database" not in json.dumps(failed), "Database detail leaked")
            require(sql("SELECT count(*) FROM media_jobs") == "0", "Failed transaction left a job")
            require(sql("SELECT state FROM media_uploads") == "OPEN", "Failed transaction submitted upload")
            require(sql("SELECT state FROM media_assets") == "UPLOADING", "Failed transaction queued asset")
        finally:
            sql("DROP TRIGGER reject_m14_job ON media_jobs; DROP FUNCTION reject_m14_job();")

        proxy.drop_response.set()
        uncertain, _ = admin.request("POST", path + "/complete", status=503)
        require(uncertain["code"] == "MEDIA_UNAVAILABLE", "Lost response did not report uncertain outcome")
        with proxy.lock:
            dropped = [(status, body) for status, body, lost in proxy.completions if lost]
        require(len(dropped) == 1 and dropped[0][0] == 202, "Response was not dropped exactly once after commit")
        job_id = str(uuid.UUID(dropped[0][1]["jobId"]))
        require(sql("SELECT id FROM media_jobs") == job_id, "Accepted job was not durable")
        proxy.drop_response.clear()
        for _ in range(2):
            recovered, _ = admin.request("POST", path + "/complete", status=202)
            require(recovered["jobId"] == job_id and recovered["attemptCount"] == 0,
                    "Completion retry changed the original job or attempt")
        denied, _ = admin.request("POST", path + "/upload-url", status=409)
        require(denied["code"] == "UPLOAD_STATE_CONFLICT", "Submitted upload issued another URL")
        signed_put()  # Previously issued credentials still accept the same checksum-bound bytes.
        status, _ = admin.request("GET", path)
        require(status["jobId"] == job_id and status["assetState"] == "QUEUED", "Replay changed admission")
        require(sql("SELECT count(*) FROM media_jobs") == "1", "Duplicate job")
        require(sql("SELECT attempt_count FROM media_jobs") == "0", "Attempt allocated before worker")
        require(sql("SELECT count(*) FROM media_assets WHERE selected_source_key IS NOT NULL") == "0",
                "Completion unexpectedly froze a source")
        require(sql("SELECT count(*) FROM media_outbox_events") == "0", "Completion emitted a processing event")
        evidence.parent.mkdir(parents=True, exist_ok=True)
        evidence.write_text(json.dumps({
            "milestone": "M14", "verifiedAt": datetime.now(timezone.utc).isoformat(),
            "runtime": "packaged Core/Media, PostgreSQL 18.6, SeaweedFS 4.46, loopback fault proxy",
            "missingSourceRejected": True, "databaseFailureRolledBack": True,
            "responseDroppedAfter202": True, "sameJobRecovered": True, "jobCount": 1,
            "attemptCount": 0, "reissueAfterSubmitDenied": True, "existingPutReplayAccepted": True,
            "frozenSources": 0, "outboxEvents": 0,
            "limits": "Local HTTP fixture; no browser, worker, FFmpeg, source freeze, READY or deployment claim"
        }, indent=2) + "\n", encoding="utf-8")
        print("PASS: M14 missing source, admission rollback, response-loss recovery, one job/zero attempts, staging replay")
    finally:
        proxy.shutdown()
        proxy.server_close()
        thread.join(timeout=5)


if __name__ == "__main__":
    main()
