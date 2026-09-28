"""Disposable real Core/Media/SeaweedFS and Chromium upload acceptance."""
import argparse
import base64
from datetime import datetime, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid

from core_http import Browser

ROOT = Path(__file__).resolve().parents[2]


class DirectCoreBrowser(Browser):
    def request(self, method, path, **kwargs):
        if path.startswith("/api/core/v1/"):
            path = path[len("/api/core"):]
        return super().request(method, path, **kwargs)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def wait_health(base):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(base + "/actuator/health/liveness", timeout=2) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, TimeoutError):
            pass
        time.sleep(1)
    raise RuntimeError("Service did not become healthy")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core-url", required=True)
    parser.add_argument("--media-url", required=True)
    parser.add_argument("--browser-port", type=int, required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--compose-file", required=True)
    args = parser.parse_args()
    require(re.fullmatch(r"libra-m11-[a-f0-9]{12}", args.project), "Expected isolated project")
    require(Path(args.compose_file).resolve() == ROOT / "infra/smoke/compose.media-m11.yaml",
            "Unexpected Compose file")
    for value in (args.core_url, args.media_url):
        require(re.fullmatch(r"http://127\.0\.0\.1:[0-9]+", value), "Service must bind loopback")
    evidence = ROOT / "target/verification/media-m11.json"
    evidence.unlink(missing_ok=True)
    compose = ["docker", "compose", "--project-name", args.project, "--file", args.compose_file]

    def sql(database, query):
        require(database in ("libra_core", "libra_media"), "Unexpected database")
        result = subprocess.run([*compose, "exec", "-T", "postgres", "psql", "-U", "postgres",
                                 "-d", database, "-At", "-v", "ON_ERROR_STOP=1", "-c", query],
                                capture_output=True, text=True, timeout=20)
        require(result.returncode == 0, "Disposable database query failed")
        return result.stdout.strip()

    wait_health(args.core_url)
    wait_health(args.media_url)
    admin = DirectCoreBrowser(args.core_url)
    admin.request("POST", "/v1/auth/login", body={
        "email": "admin@m11.example.test", "password": os.environ["LIBRA_M11_ADMIN_PASSWORD"]})
    movie, _ = admin.request("POST", "/v1/admin/catalog", status=201, body={
        "kind": "MOVIE", "metadata": {
            "title": "M11 browser upload fixture", "description": "Disposable browser upload",
            "genres": ["drama"], "releaseYear": 2026, "language": "en",
            "credits": [], "imageReferences": [], "accessTier": "FREE"}})
    clip_path = ROOT / "infra/smoke/fixtures/m11-test-clip.mp4"
    require(clip_path.is_file(), "MP4 test clip is unavailable")
    clip = clip_path.read_bytes()
    require(0 < len(clip) < 1024 * 1024, "Unexpected test clip size")
    upload_path = f"/v1/admin/catalog/{movie['id']}/uploads"
    created, _ = admin.request("POST", upload_path, status=201, body={
        "requestId": str(uuid.uuid4()), "expectedVersion": movie["version"],
        "byteLength": len(clip), "sha256": hashlib.sha256(clip).hexdigest()})
    upload_id = str(uuid.UUID(created["uploadId"]))
    grant, headers = admin.request("POST", f"/v1/admin/uploads/{upload_id}/upload-url", status=200)
    require(headers.get("Cache-Control") == "no-store", "Signed URL response was cacheable")
    require(grant["uploadId"] == upload_id and grant["method"] == "PUT", "Grant identity or method changed")
    require(grant["url"].startswith(os.environ["MEDIA_S3_BROWSER_ENDPOINT"] + "/libra-source/staging/"),
            "Grant did not use browser S3 staging endpoint")
    require(os.environ["MEDIA_S3_INTERNAL_ENDPOINT"] != os.environ["MEDIA_S3_BROWSER_ENDPOINT"],
            "Internal and browser S3 endpoints were not distinct")
    expiry = datetime.fromisoformat(grant["expiresAt"].replace("Z", "+00:00"))
    session_expiry = datetime.fromisoformat(created["expiresAt"].replace("Z", "+00:00"))
    require(datetime.now(timezone.utc) < expiry <= session_expiry
            and (expiry - datetime.now(timezone.utc)).total_seconds() <= 900,
            "Grant exceeded session or 15-minute expiry")
    second, _ = admin.request("POST", f"/v1/admin/uploads/{upload_id}/upload-url", status=200)
    require(second["uploadId"] == upload_id and second["expiresAt"] <= created["expiresAt"],
            "Reissue changed the session identity or expiry")

    page = """<!doctype html><main id='status'>pending</main><script>
    const grant = GRANT;
    const raw = atob(CLIP);
    const bytes = Uint8Array.from(raw, c => c.charCodeAt(0));
    fetch(grant.url, {method:'PUT', mode:'cors', credentials:'omit',
      headers:grant.requiredHeaders, body:new Blob([bytes], {type:'video/mp4'})})
      .then(r => { document.querySelector('#status').dataset.result = r.ok ? 'ok' : 'error'; })
      .catch(() => { document.querySelector('#status').dataset.result = 'error'; });
    </script>""".replace("GRANT", json.dumps(grant)).replace("CLIP", json.dumps(base64.b64encode(clip).decode()))

    class Page(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != "/":
                self.send_error(404)
                return
            body = page.encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", args.browser_port), Page)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    chrome = Path("C:/Program Files/Google/Chrome/Application/chrome.exe")
    require(chrome.is_file(), "Chrome browser is unavailable")
    profile = ROOT / "target/verification/m11-chrome-profile"
    if profile.exists():
        shutil.rmtree(profile)
    try:
        result = subprocess.run([str(chrome), "--headless=new", "--disable-gpu", "--no-first-run",
                                 "--no-default-browser-check", "--virtual-time-budget=12000",
                                 "--user-data-dir=" + str(profile), "--dump-dom",
                                 f"http://127.0.0.1:{args.browser_port}/"],
                                capture_output=True, timeout=45)
        require(result.returncode == 0 and b'data-result="ok"' in result.stdout,
                "Chromium preflight or signed PUT failed")
    finally:
        server.shutdown()
        for _ in range(5):
            shutil.rmtree(profile, ignore_errors=True)
            if not profile.exists():
                break
            time.sleep(0.2)
        require(not profile.exists(), "Disposable browser profile was not removed")

    status, _ = admin.request("GET", f"/v1/admin/uploads/{upload_id}")
    require(status["uploadState"] == "OPEN" and status["assetState"] == "UPLOADING"
            and status["jobId"] is None, "PUT incorrectly made content playable or queued")
    catalog, _ = admin.request("GET", f"/v1/admin/catalog/{movie['id']}")
    require(catalog["active"] is None and catalog["published"] is None,
            "PUT changed active binding or publication")
    require(sql("libra_core", "SELECT count(*) FROM catalog_upload_intents") == "1", "Core intent count")
    require(sql("libra_media", "SELECT count(*) FROM media_uploads") == "1", "Media upload count")
    require(sql("libra_media", "SELECT count(*) FROM media_jobs") == "0", "Unexpected Media job")
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text(json.dumps({
        "result": "MEDIA_M11_SMOKE_PASS", "checkedAt": datetime.now(timezone.utc).isoformat(),
        "browser": "Google Chrome headless", "clipBytes": len(clip),
        "checks": ["Live ADMIN/CSRF grant through Core and Media",
                   "Different internal and browser S3 endpoints",
                   "Real Chromium CORS preflight and direct signed PUT to SeaweedFS 4.46",
                   "No job, active asset or publication after PUT"]
    }, indent=2) + "\n", encoding="utf-8")
    print("MEDIA_M11_SMOKE_PASS", flush=True)


if __name__ == "__main__":
    main()
