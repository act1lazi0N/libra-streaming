"""Check that a queued upload remains visible after restarting packaged Media."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import uuid

from media_m11_http import DirectCoreBrowser, require, wait_health


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core-url", required=True)
    parser.add_argument("--media-url", required=True)
    parser.add_argument("--upload-id", required=True)
    parser.add_argument("--project", required=True)
    parser.add_argument("--compose-file", required=True)
    args = parser.parse_args()
    require(re.fullmatch(r"libra-m11-[a-f0-9]{12}", args.project), "Expected isolated project")
    upload_id = str(uuid.UUID(args.upload_id))
    wait_health(args.media_url)
    admin = DirectCoreBrowser(args.core_url)
    admin.request("POST", "/v1/auth/login", body={
        "email": "admin@m11.example.test", "password": os.environ["LIBRA_M11_ADMIN_PASSWORD"]})
    status, _ = admin.request("GET", f"/v1/admin/uploads/{upload_id}")
    require(status["uploadState"] == "SUBMITTED" and status["assetState"] == "QUEUED"
            and status["jobId"] and status["attemptCount"] == 0,
            "Queued job was not readable after Media restart")
    duplicate, _ = admin.request("POST", f"/v1/admin/uploads/{upload_id}/complete", status=202)
    require(duplicate["jobId"] == status["jobId"], "Restart changed completion identity")
    compose = ["docker", "compose", "--project-name", args.project, "--file", args.compose_file]
    query = subprocess.run([*compose, "exec", "-T", "postgres", "psql", "-U", "postgres",
                            "-d", "libra_media", "-At", "-v", "ON_ERROR_STOP=1", "-c",
                            "SELECT count(*) FROM media_jobs"], capture_output=True, text=True, timeout=20)
    require(query.returncode == 0 and query.stdout.strip() == "1", "Durable job count changed after restart")
    evidence = Path(__file__).resolve().parents[2] / "target/verification/media-m13.json"
    result = json.loads(evidence.read_text(encoding="utf-8"))
    require(result.get("result") == "MEDIA_M13_SMOKE_PASS", "M13 initial evidence is missing")
    result["checks"].append("Same queued job visible through Core and PostgreSQL after Media restart")
    evidence.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("MEDIA_M13_RESTART_PASS", flush=True)


if __name__ == "__main__":
    main()
