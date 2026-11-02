"""M36 retained-state restart of the packaged stack through the repository ingress. Containers and networks are
removed while named volumes and process keys are retained. Never print credentials, tickets, hashes or bodies."""
import argparse
import base64
from contextlib import redirect_stdout
import hashlib
import io
import json
import os
import re
import secrets
import subprocess
import time
import uuid

from core_http import require, wait_until
from media_m29_http import ROOT, SESSION, TICKET_COOKIE, Viewer, problem, play
from media_m34_faults import Stack

CHECKS = []


def passed(message):
    CHECKS.append(message)
    print(f"MEDIA_M36_RESTART_PASS {message}", flush=True)


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--mail-url", required=True)
    parser.add_argument("--project", required=True)
    args = parser.parse_args()
    require(re.fullmatch(r"libra-m36-[a-f0-9]{12}", args.project), "Expected an isolated M36 project")
    for url in (args.base_url, args.mail_url):
        require(re.fullmatch(r"http://127\.0\.0\.1:[0-9]+", url), "Smoke URLs must be loopback")
    return args


def flyway(stack):
    histories = {}
    for service, database in (("core", "libra_core"), ("media", "libra_media")):
        expected = len(list((ROOT / f"services/{service}/src/main/resources/db/migration").glob("V*__*.sql")))
        history = stack.sql(database, "SELECT count(*), max(installed_rank), bool_and(success) "
                            "FROM flyway_schema_history")
        require(expected > 0 and history == f"{expected}|{expected}|t", "Flyway history differs from packaged migrations")
        histories[database] = history
    return histories


def register(args):
    """Retain credentials only in memory so this Premium account can log in after the restart."""
    viewer = Viewer(args.base_url)
    email = "viewer-" + uuid.uuid4().hex + "@m36.example.test"
    password = secrets.token_urlsafe(32)
    viewer.core("POST", "/auth/register", status=202,
                body={"email": email, "displayName": "M36 viewer", "password": password})
    mail = Viewer(args.mail_url)

    def token():
        status, _, raw = mail.open("GET", "/api/v1/messages")
        require(status == 200, "Mailpit messages unavailable")
        for message in json.loads(raw).get("messages", []):
            if any(recipient.get("Address") == email for recipient in message.get("To", [])):
                status, _, body = mail.open("GET", "/api/v1/message/" + message["ID"])
                require(status == 200, "Mailpit message unavailable")
                match = re.search(r"token=([A-Za-z0-9_-]{43})", json.loads(body).get("Text", ""))
                if match:
                    return match[1]
        return None

    viewer.core("POST", "/auth/verify-email", status=204, body={"token": wait_until(token, "No verification mail")})
    viewer.core("POST", "/auth/login", body={"email": email, "password": password})
    viewer.core("POST", "/subscriptions/simulate", body={"plan": "PREMIUM_30_DAYS"},
                headers={"Idempotency-Key": str(uuid.uuid4())})
    profiles, _ = viewer.core("GET", "/profiles")
    return email, password, str(uuid.UUID(profiles[0]["id"]))


def login_admin(base):
    admin = Viewer(base)
    admin.core("POST", "/auth/login", body={"email": "admin@m29.example.test",
                                            "password": os.environ["LIBRA_M29_ADMIN_PASSWORD"]})
    return admin


def core_counts(stack, profile):
    account = f"SELECT account_id FROM profiles WHERE id = '{uuid.UUID(profile)}'"
    return tuple(int(stack.sql("libra_core", query)) for query in (
        f"SELECT count(*) FROM identity_accounts WHERE id IN ({account})",
        f"SELECT count(*) FROM identity_sessions WHERE account_id IN ({account}) "
        "AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP",
        f"SELECT count(*) FROM playback_sessions WHERE account_id IN ({account})"))


def outbox_counts(stack):
    return tuple(int(value) for value in stack.sql("libra_media", "SELECT count(*), "
                 "count(*) FILTER (WHERE delivery_state = 'SENT') FROM media_outbox_events").split("|"))


def offsets(stack):
    raw = stack.docker("exec", "-T", "kafka", "/opt/kafka/bin/kafka-get-offsets.sh",
                       "--bootstrap-server", "kafka:9092", "--topic", "media.assets.v1")
    lines = raw.strip().splitlines()
    require(all(re.fullmatch(r"media\.assets\.v1:[0-9]+:[0-9]+", line) for line in lines),
            "Kafka offsets response changed")
    ends = {int(line.split(":")[1]): int(line.split(":")[2]) for line in lines}
    require(len(ends) == len(lines), "Kafka returned duplicate partitions")
    return ends


def group_offsets(stack):
    # Core's projection group: a lost commit would make Core replay the topic from the earliest offset unseen.
    raw = stack.docker("exec", "-T", "kafka", "/opt/kafka/bin/kafka-consumer-groups.sh", "--bootstrap-server",
                       "kafka:9092", "--describe", "--group", "core-media-projection-v1")
    rows = re.findall(r"^core-media-projection-v1\s+media\.assets\.v1\s+([0-9]+)\s+([0-9-]+)\s+([0-9-]+)\s",
                      raw, re.M)
    # No commit yet ("-") reads as no offsets, which never equals a snapshot.
    return {int(partition): int(current) for partition, current, _ in rows if current.isdigit()}


def ticket_times(ticket):
    parts = ticket.split(".")
    require(len(parts) == 3, "Playback ticket is not a JWT")
    payload = json.loads(base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4)))
    issued, expires = payload.get("iat"), payload.get("exp")
    require(type(issued) is int and type(expires) is int and 0 < expires - issued <= 300,
            "Playback ticket exceeds the lifetime cap")
    return issued, expires


def ingress_hashes(viewer, path, segments):
    hashes = []
    for name in ("master.m3u8", "rendition.m3u8", *segments):
        status, _, raw = viewer.open("GET", path + name)
        require(status == 200, "Retained HLS bytes unavailable")
        hashes.append(hashlib.sha256(raw).digest())
    return hashes


def database_ownership(stack):
    source = (ROOT / "infra/postgres/init/001-create-databases.sql").read_text(encoding="utf-8")
    passwords = dict(re.findall(r"^CREATE USER (libra_core|libra_media) WITH PASSWORD '([^']+)';$", source, re.M))
    require(set(passwords) == {"libra_core", "libra_media"}, "Database owner credentials not found")
    for owner, other in (("libra_core", "libra_media"), ("libra_media", "libra_core")):
        environment = {**os.environ, "PGPASSWORD": passwords[owner]}
        for database in (owner, other):
            result = subprocess.run([*stack.compose, "exec", "-T", "-e", "PGPASSWORD", "postgres", "psql",
                                     "-h", "localhost", "-U", owner, "-d", database, "-At", "-v",
                                     "ON_ERROR_STOP=1", "-c", "SELECT 1"], env=environment,
                                    capture_output=True, text=True, timeout=30)
            if database == owner:
                require(result.returncode == 0 and result.stdout.strip() == "1", "Owner cannot connect to own database")
            else:
                require(result.returncode != 0 and "permission denied" in result.stderr.lower()
                        and "database" in result.stderr.lower() and "connect" in result.stderr.lower(),
                        "Cross-database CONNECT was not denied")


def main():
    args = arguments()
    evidence = ROOT / "target/verification/media-m36.json"
    evidence.unlink(missing_ok=True)
    stack = Stack(args)
    stack.wait_services()
    histories = flyway(stack)
    passed("Fresh Flyway history matches packaged migrations")

    # M29 prints a dynamic segment count; keep its output and all shared-helper errors out of this driver's log.
    with redirect_stdout(io.StringIO()):
        played = play(argparse.Namespace(base_url=args.base_url, mail_url=args.mail_url, project=args.project))
    viewer = played["viewer"]
    old_ticket = viewer.ticket(played["session_id"])
    old_times = ticket_times(old_ticket)
    hashes = ingress_hashes(viewer, played["stream_path"], played["segments"])
    require(hashes[:2] == [hashlib.sha256(played[name]).digest() for name in ("master", "rendition")],
            "M29 playlist bytes changed before the snapshot")
    passed("M29 playback and ingress hashes recorded")
    email, password, profile = register(args)
    passed("Retained Premium viewer registered")

    content_id = str(uuid.UUID(played["content_id"]))
    admin = login_admin(args.base_url)
    movie, _ = admin.core("GET", f"/admin/catalog/{content_id}")
    candidate = movie["candidate"]
    candidate_state = (candidate["state"], candidate["assetVersion"], candidate["projectionVersion"])
    require(candidate_state[0] == "READY", "Core candidate is not READY")
    asset_id = str(uuid.UUID(candidate["assetId"]))
    asset_version = int(candidate["assetVersion"])
    asset_query = ("SELECT state, asset_version FROM media_assets "
                   f"WHERE asset_id = '{asset_id}' AND asset_version = {asset_version} AND content_id = '{content_id}'")
    asset = stack.sql("libra_media", asset_query)
    require(asset == f"READY|{asset_version}", "Media asset is not READY at the candidate version")
    upload_id = str(uuid.UUID(stack.sql("libra_media", "SELECT id FROM media_uploads "
                                       f"WHERE asset_id = '{asset_id}' AND asset_version = {asset_version}")))

    def unsent():
        status, _ = admin.core("GET", f"/admin/uploads/{upload_id}")
        return status["unsentEvents"]

    wait_until(lambda: unsent() == 0, "Media status still has unsent events", 60)
    before_outbox = outbox_counts(stack)
    require(before_outbox[0] > 0 and before_outbox[0] == before_outbox[1], "Media outbox is not fully sent")
    before_offsets = offsets(stack)
    require(sum(before_offsets.values()) > 0, "Media topic has no acknowledged records")
    # The listener commits after it has projected the record, so the commit can trail the READY candidate briefly.
    wait_until(lambda: group_offsets(stack) == before_offsets, "Core has not committed every Media event", 30)
    before_group = group_offsets(stack)
    profiles = (played["profile"], profile)
    before_core = [core_counts(stack, value) for value in profiles]
    require(all(counts[0] == 1 and counts[1] > 0 for counts in before_core) and before_core[0][2] > 0,
            "Core viewer state missing before restart")
    passed("READY assets and candidates with zero unsent events snapshotted")
    passed("Outbox Kafka and Core viewer counts snapshotted")

    stack.docker("down", "--timeout", "15", timeout=360)
    stack.docker("up", "-d", "--wait", "--wait-timeout", "240", "postgres", "kafka", "mailpit", "seaweedfs",
                 "s3-gateway", "core", "media", "nginx", timeout=360)
    for attribute, service, port in (("base_url", "nginx", "80"), ("mail_url", "mailpit", "8025")):
        address = stack.docker("port", service, port).strip().splitlines()[-1].strip()
        require(re.fullmatch(r"127\.0\.0\.1:[0-9]+", address), "Restarted service port must be loopback")
        setattr(args, attribute, "http://" + address)
    stack.public = Viewer(args.base_url)
    stack.wait_services()
    passed("Retained volumes restarted and ingress health restored")

    require(flyway(stack) == histories, "Flyway history changed after restart")
    passed("Flyway history unchanged after restart")
    require([core_counts(stack, value) for value in profiles] == before_core, "Core viewer counts changed after restart")
    passed("Core accounts active sessions and playback counts retained")
    admin = login_admin(args.base_url)
    require(stack.sql("libra_media", asset_query) == asset and unsent() == 0, "Media READY state or unsent count changed")
    require(outbox_counts(stack) == before_outbox, "Media outbox counts changed after restart")
    passed("Media READY version zero unsent events and SENT counts retained")
    after_offsets = offsets(stack)
    # Partition numbers and end offsets only: safe to print, and the diagnosis needs them.
    require(after_offsets == before_offsets,
            f"Kafka end offsets changed after restart: {before_offsets} -> {after_offsets}")
    after_group = group_offsets(stack)
    require(after_group == before_group, f"Core committed offsets changed after restart: {before_group} -> {after_group}")
    passed("Kafka end and Core committed offsets retained without republication")
    movie, _ = admin.core("GET", f"/admin/catalog/{content_id}")
    current = movie["candidate"]
    require(current["id"] == candidate["id"] and current["assetId"] == asset_id
            and (current["state"], current["assetVersion"], current["projectionVersion"]) == candidate_state,
            "Core candidate changed after restart")
    passed("Core READY candidate versions retained")

    viewer = Viewer(args.base_url)
    viewer.core("POST", "/auth/login", body={"email": email, "password": password})
    current_profiles, _ = viewer.core("GET", "/profiles")
    require(any(value["id"] == profile for value in current_profiles), "Retained viewer profile missing")
    session, _ = viewer.core("POST", "/playback/sessions", status=201,
                             body={"profileId": profile, "contentId": content_id})
    require(re.fullmatch(SESSION, session["sessionId"]), "New session identity is not a UUID")
    path = f"/api/media/v1/streams/{session['sessionId']}/"
    require(session["manifestPath"] == path + "master.m3u8", "New manifest path changed")
    passed("Retained viewer login and new playback admission succeeded")
    require(ingress_hashes(viewer, path, played["segments"]) == hashes, "Retained HLS hashes differ")
    passed("Master rendition and every segment hash retained")
    ticket = viewer.ticket(session["sessionId"])
    decoded = subprocess.run([os.environ.get("LIBRA_FFMPEG_PATH") or "ffmpeg", "-v", "error", "-nostdin", "-xerror",
                              "-headers", f"Cookie: {TICKET_COOKIE}={ticket}\r\n", "-i", args.base_url + session["manifestPath"],
                              "-map", "0", "-f", "null", "-"], capture_output=True, timeout=180)
    require(decoded.returncode == 0 and not decoded.stderr.strip(), "Retained HLS did not decode cleanly")
    passed("New ticket playback decoded through ingress")

    old_viewer = Viewer(args.base_url)
    before_request = time.time()
    response = old_viewer.open("GET", played["stream_path"] + "master.m3u8",
                               headers={"Cookie": f"{TICKET_COOKIE}={old_ticket}"})
    status, _, raw = response
    # Media compares exp with its own (Docker VM) clock; within 10 s of exp either answer is correct.
    if abs(before_request - old_times[1]) < 10:
        require(status in (200, 401, 403), "Old ticket answer unexpected near its expiry")
    elif before_request < old_times[1]:
        require(status == 200 and hashlib.sha256(raw).digest() == hashes[0], "Unexpired old ticket playback changed")
    else:
        require(status in (401, 403), "Expired old ticket was not refused")
        problem(*response, status, "AUTHENTICATION_REQUIRED" if status == 401 else "ACCESS_DENIED")
    passed("Old ticket lifetime expiry and retained bytes respected")
    database_ownership(stack)
    passed("Owners connect only to their own databases")

    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text(json.dumps({"result": "MEDIA_M36_RESTART_PASS", "checks": CHECKS,
                                    **({"runId": os.environ["LIBRA_VERIFY_RUN_ID"]}
                                       if os.environ.get("LIBRA_VERIFY_RUN_ID") else {})}, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    try:
        main()
    except Exception as failure:
        # Shared helpers can include dynamic paths in exceptions; never expose their messages or subprocess output.
        try:
            (ROOT / "target/verification/media-m36.json").unlink(missing_ok=True)
        except OSError:
            pass
        # require() messages are fixed strings; anything else is reported by type only.
        reason = str(failure) if type(failure) is RuntimeError else type(failure).__name__
        print(f"FAIL after {len(CHECKS)} checks: {reason}", flush=True)
        raise SystemExit(1) from None
