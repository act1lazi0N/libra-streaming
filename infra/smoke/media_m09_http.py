"""Disposable M09 Core-to-Media HTTP and separate-database acceptance."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import subprocess
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


def wait_health(base, label):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(base + "/actuator/health/liveness", timeout=2) as response:
                if response.status == 200:
                    return
        except (urllib.error.URLError, TimeoutError):
            pass
        time.sleep(1)
    raise RuntimeError(label + " did not become healthy")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core-url", required=True)
    parser.add_argument("--media-url", required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--compose-file", required=True)
    args = parser.parse_args()
    for value in (args.core_url, args.media_url):
        require(re.fullmatch(r"http://127\.0\.0\.1:[0-9]+", value), "Smoke services must bind loopback")
    require(re.fullmatch(r"libra-m09-[a-f0-9]{12}", args.project), "Expected isolated project")
    compose_file = Path(args.compose_file).resolve()
    require(compose_file == ROOT / "infra/smoke/compose.media-m09.yaml", "Unexpected smoke Compose file")
    evidence = ROOT / "target/verification/media-m09.json"
    evidence.unlink(missing_ok=True)
    compose = ["docker", "compose", "--project-name", args.project, "--file", str(compose_file)]

    def sql(database, query):
        require(database in ("libra_core", "libra_media"), "Unexpected database")
        result = subprocess.run([*compose, "exec", "-T", "postgres", "psql", "-U", "postgres",
                                 "-d", database, "-At", "-v", "ON_ERROR_STOP=1", "-c", query],
                                capture_output=True, text=True, timeout=20)
        require(result.returncode == 0, "Disposable PostgreSQL query failed")
        return result.stdout.strip()

    wait_health(args.core_url, "Core")
    wait_health(args.media_url, "Media")
    admin = DirectCoreBrowser(args.core_url)
    admin.request("POST", "/v1/auth/login", body={
        "email": "admin@m09.example.test", "password": os.environ["LIBRA_M09_ADMIN_PASSWORD"]})
    movie, _ = admin.request("POST", "/v1/admin/catalog", status=201, body={
        "kind": "MOVIE", "metadata": {
            "title": "M09 upload fixture", "description": "Disposable service integration",
            "genres": ["drama"], "releaseYear": 2026, "language": "en",
            "credits": [], "imageReferences": [], "accessTier": "FREE"}})
    content_id = str(uuid.UUID(movie["id"]))
    request_id = str(uuid.uuid4())
    payload = {
        "requestId": request_id, "expectedVersion": movie["version"],
        "byteLength": 1024, "sha256": "a" * 64}
    path = f"/v1/admin/catalog/{content_id}/uploads"
    first, first_headers = admin.request("POST", path, body=payload, status=201)
    require(first_headers.get("Cache-Control") == "no-store", "Upload response must not be cached")
    require(first["uploadState"] == "OPEN" and first["assetState"] == "UPLOADING"
            and first["jobId"] is None and first["attemptCount"] == 0,
            "Creation must report an unplayable upload")
    upload_id = str(uuid.UUID(first["uploadId"]))
    repeated, _ = admin.request("POST", path, body=payload, status=200)
    require(repeated == first, "Idempotent POST returned a different upload")
    recovered, _ = admin.request("GET", f"/v1/admin/uploads/{upload_id}")
    require(recovered == first, "Status read returned a different binding or state")
    conflict, _ = admin.request("POST", path, body={**payload, "byteLength": 2048}, status=409)
    require(conflict["code"] == "IDEMPOTENCY_CONFLICT", "Changed request fingerprint was accepted")

    catalog, _ = admin.request("GET", f"/v1/admin/catalog/{content_id}")
    require(catalog["candidate"]["id"] == first["bindingId"] and catalog["active"] is None
            and catalog["published"] is None, "Upload creation changed publication or active asset")
    require(sql("libra_core", "SELECT count(*) FROM catalog_upload_intents") == "1", "Core intent count is not one")
    require(sql("libra_core", "SELECT count(*) FROM catalog_media_bindings") == "1", "Core candidate count is not one")
    require(sql("libra_media", "SELECT count(*) FROM media_uploads") == "1", "Media upload count is not one")
    require(sql("libra_media", "SELECT count(*) FROM media_assets") == "1", "Media asset count is not one")
    require(sql("libra_media", "SELECT count(*) FROM media_jobs") == "0", "Creation queued a job")
    require(sql("libra_media", "SELECT count(*) FROM media_outbox_events") == "0", "Creation published an event")
    core_tuple = sql("libra_core", """
        SELECT i.id || '|' || i.content_id || '|' || i.binding_id || '|' ||
            b.asset_id || '|' || b.asset_version
        FROM catalog_upload_intents i JOIN catalog_media_bindings b ON b.id = i.binding_id
        """)
    media_tuple = sql("libra_media", """
        SELECT id || '|' || content_id || '|' || binding_id || '|' || asset_id || '|' || asset_version
        FROM media_uploads
        """)
    require(core_tuple == media_tuple, "Core and Media persisted different identity tuples")
    anonymous = Browser(args.media_url)
    anonymous.request("GET", f"/internal/v1/uploads/{upload_id}", status=401)
    require(sql("libra_media", "SELECT count(*) FROM media_uploads") == "1", "Anonymous read changed upload count")
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text(json.dumps({
        "result": "MEDIA_M09_SMOKE_PASS",
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "checks": [
            "Real Core and Media HTTP with distinct PostgreSQL databases",
            "ADMIN cookie and CSRF create; Core-to-Media and Media-to-Core RS256 service calls",
            "Same request ID retry and status preserve exact tuple; changed fingerprint conflicts",
            "One intent, candidate, Media upload and asset; zero jobs and outbox events",
            "No active/published asset; anonymous Media control read denied",
        ],
    }, indent=2) + "\n", encoding="utf-8")
    print("MEDIA_M09_SMOKE_PASS", flush=True)


if __name__ == "__main__":
    main()
