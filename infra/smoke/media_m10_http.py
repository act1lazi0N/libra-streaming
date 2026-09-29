"""Deterministic Core-to-Media fault checkpoints over real HTTP and PostgreSQL."""
import argparse
from datetime import datetime, timezone
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import threading
import uuid

from media_m09_http import DirectCoreBrowser, require, wait_health

ROOT = Path(__file__).resolve().parents[2]


class FaultProxy(ThreadingHTTPServer):
    def __init__(self, port, media_port):
        super().__init__(("127.0.0.1", port), FaultHandler)
        self.media_port = media_port
        self.mode = "PASS"
        self.forwarded = []
        self.lock = threading.Lock()

    def set_mode(self, mode):
        with self.lock:
            self.mode = mode


class FaultHandler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_GET(self):
        self.forward()

    def do_PUT(self):
        self.forward()

    def forward(self):
        with self.server.lock:
            mode = self.server.mode
        if mode == "BEFORE_COMMIT":
            self.send_response(503)
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        length = int(self.headers.get("Content-Length", "0"))
        require(length <= 65536, "Unexpected oversized internal request")
        body = self.rfile.read(length) if length else None
        connection = http.client.HTTPConnection("127.0.0.1", self.server.media_port, timeout=10)
        try:
            headers = {"Authorization": self.headers.get("Authorization", ""),
                       "Content-Type": self.headers.get("Content-Type", "application/json")}
            connection.request(self.command, self.path, body=body, headers=headers)
            response = connection.getresponse()
            data = response.read(65537)
            require(len(data) <= 65536, "Unexpected oversized Media response")
            with self.server.lock:
                self.server.forwarded.append((self.command, self.path, response.status, mode))
            if mode == "AFTER_COMMIT" and self.command == "PUT":
                # Media's response was read only after its transaction completed.
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
    parser.add_argument("--core-url", required=True)
    parser.add_argument("--media-url", required=True)
    parser.add_argument("--proxy-port", type=int, required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--compose-file", required=True)
    args = parser.parse_args()
    require(re.fullmatch(r"libra-m10-[a-f0-9]{12}", args.project), "Expected isolated project")
    for url in (args.core_url, args.media_url):
        require(re.fullmatch(r"http://127\.0\.0\.1:[0-9]+", url), "Smoke services must bind loopback")
    require(1 <= args.proxy_port <= 65535, "Invalid proxy port")
    compose_file = Path(args.compose_file).resolve()
    require(compose_file == ROOT / "infra/smoke/compose.media-m09.yaml", "Unexpected smoke Compose file")
    evidence = ROOT / "target/verification/media-m10.json"
    evidence.unlink(missing_ok=True)
    compose = ["docker", "compose", "--project-name", args.project, "--file", str(compose_file)]

    def sql(database, query):
        require(database in ("libra_core", "libra_media"), "Unexpected database")
        result = subprocess.run([*compose, "exec", "-T", "postgres", "psql", "-U", "postgres",
                                 "-d", database, "-At", "-v", "ON_ERROR_STOP=1", "-c", query],
                                capture_output=True, text=True, timeout=20)
        require(result.returncode == 0, "Disposable PostgreSQL query failed")
        return result.stdout.strip()

    proxy = FaultProxy(args.proxy_port, int(args.media_url.rsplit(":", 1)[1]))
    thread = threading.Thread(target=proxy.serve_forever, daemon=True)
    thread.start()
    try:
        wait_health(args.core_url, "Core")
        wait_health(args.media_url, "Media")
        admin = DirectCoreBrowser(args.core_url)
        admin.request("POST", "/v1/auth/login", body={
            "email": "admin@m09.example.test", "password": os.environ["LIBRA_M09_ADMIN_PASSWORD"]})

        def movie(title):
            result, _ = admin.request("POST", "/v1/admin/catalog", status=201, body={
                "kind": "MOVIE", "metadata": {"title": title, "description": "Fault checkpoint fixture",
                "genres": ["drama"], "releaseYear": 2026, "language": "en", "credits": [],
                "imageReferences": [], "accessTier": "FREE"}})
            return result

        def payload(item, suffix):
            return {"requestId": str(uuid.uuid4()), "expectedVersion": item["version"],
                    "byteLength": 1024, "sha256": suffix * 64}

        committed = movie("M10 response lost after commit")
        first = payload(committed, "a")
        first_path = f"/v1/admin/catalog/{committed['id']}/uploads"
        proxy.set_mode("AFTER_COMMIT")
        failed, _ = admin.request("POST", first_path, body=first, status=503)
        require(failed["code"] == "MEDIA_UNAVAILABLE", "Lost response must expose uncertain outcome")
        first_id = sql("libra_core", f"SELECT id FROM catalog_upload_intents WHERE request_id = '{first['requestId']}'")
        require(str(uuid.UUID(first_id)) == first_id, "Core intent did not commit")
        require(sql("libra_media", f"SELECT count(*) FROM media_uploads WHERE id = '{first_id}'") == "1",
                "Media did not commit before response loss")
        with proxy.lock:
            require(any(method == "PUT" and status == 201 and mode == "AFTER_COMMIT"
                        for method, _, status, mode in proxy.forwarded), "Fault was not injected after Media commit")
        proxy.set_mode("PASS")
        recovered, _ = admin.request("POST", first_path, body=first, status=200)
        require(recovered["uploadId"] == first_id, "Retry changed committed identity")
        status, _ = admin.request("GET", f"/v1/admin/uploads/{first_id}")
        require(status == recovered, "Recovered status changed committed identity")
        changed, _ = admin.request("POST", first_path, body={**first, "byteLength": 2048}, status=409)
        require(changed["code"] == "IDEMPOTENCY_CONFLICT", "Changed fingerprint did not conflict")

        pending = movie("M10 failure before Media commit")
        second = payload(pending, "b")
        second_path = f"/v1/admin/catalog/{pending['id']}/uploads"
        proxy.set_mode("BEFORE_COMMIT")
        failed, _ = admin.request("POST", second_path, body=second, status=503)
        require(failed["code"] == "MEDIA_UNAVAILABLE", "Precommit fault must expose uncertain outcome")
        second_id = sql("libra_core", f"SELECT id FROM catalog_upload_intents WHERE request_id = '{second['requestId']}'")
        require(sql("libra_media", f"SELECT count(*) FROM media_uploads WHERE id = '{second_id}'") == "0",
                "Media row appeared before forwarding")
        proxy.set_mode("PASS")
        resumed, _ = admin.request("GET", f"/v1/admin/uploads/{second_id}")
        require(resumed["uploadId"] == second_id and resumed["uploadState"] == "OPEN",
                "Status recovery did not provision the same intent")
        repeated, _ = admin.request("POST", second_path, body=second, status=200)
        require(repeated == resumed, "POST after status recovery changed identity")

        require(sql("libra_core", "SELECT count(*) FROM catalog_upload_intents") == "2", "Duplicate Core intent")
        require(sql("libra_core", "SELECT count(*) FROM catalog_media_bindings") == "2", "Duplicate Core binding")
        require(sql("libra_media", "SELECT count(*) FROM media_uploads") == "2", "Duplicate Media upload")
        require(sql("libra_media", "SELECT count(*) FROM media_assets") == "2", "Duplicate Media asset")
        require(sql("libra_media", "SELECT count(*) FROM media_jobs") == "0", "Unexpected job")
        require(sql("libra_media", "SELECT count(*) FROM media_outbox_events") == "0", "Unexpected outbox event")
        core_rows = sql("libra_core", """SELECT i.id || '|' || i.content_id || '|' || i.binding_id || '|' ||
            b.asset_id || '|' || b.asset_version FROM catalog_upload_intents i
            JOIN catalog_media_bindings b ON b.id = i.binding_id ORDER BY i.id""")
        media_rows = sql("libra_media", """SELECT id || '|' || content_id || '|' || binding_id || '|' ||
            asset_id || '|' || asset_version FROM media_uploads ORDER BY id""")
        require(core_rows == media_rows, "Recovered Core and Media identities differ")
        evidence.parent.mkdir(parents=True, exist_ok=True)
        evidence.write_text(json.dumps({"result": "MEDIA_M10_SMOKE_PASS",
            "checkedAt": datetime.now(timezone.utc).isoformat(),
            "faults": ["response lost after Media committed", "request blocked before Media commit"],
            "recoveredUploadIds": [first_id, second_id],
            "counts": {"coreIntents": 2, "coreBindings": 2, "mediaUploads": 2, "mediaAssets": 2,
                       "mediaJobs": 0, "mediaOutboxEvents": 0}}, indent=2) + "\n", encoding="utf-8")
        print("MEDIA_M10_SMOKE_PASS", flush=True)
    finally:
        proxy.shutdown()
        proxy.server_close()
        thread.join(timeout=5)


if __name__ == "__main__":
    main()
