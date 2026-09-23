"""Disposable local Core acceptance via real ingress. Never print credentials/bodies."""
import argparse
from datetime import datetime, timezone
import http.cookiejar
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
CHECKS = []


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def passed(message):
    CHECKS.append(message)
    print(f"PASS: {message}", flush=True)


class Browser:
    def __init__(self, base):
        self.base = base
        self.cookies = http.cookiejar.CookieJar()
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(self.cookies))

    def request(self, method, path, body=None, status=200, csrf=True, headers=None):
        headers = dict(headers or {})
        if method != "GET" and csrf:
            token, _ = self.request("GET", "/api/core/v1/auth/csrf")
            headers[token["headerName"]] = token["token"]
        data = None if body is None else json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            response = self.http.open(request, timeout=10)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            require(response.status == status, f"{method} {path}: expected {status}, got {response.status}")
            raw = response.read(1_048_577)
            require(len(raw) <= 1_048_576, "Unexpected oversized smoke response")
            value = json.loads(raw) if raw and "json" in response.headers.get("Content-Type", "") else None
            return value, response.headers

    def api(self, method, path, **kwargs):
        return self.request(method, "/api/core/v1" + path, **kwargs)[0]


def wait_until(check, message, seconds=90):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            result = check()
            if result:
                return result
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(1)
    raise RuntimeError(message)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--mail-url", required=True)
    parser.add_argument("--project", required=True)
    args = parser.parse_args()
    require(re.fullmatch(r"libra-smoke-[a-f0-9]{12}", args.project), "Expected an isolated smoke project")
    for url in (args.base_url, args.mail_url):
        require(re.fullmatch(r"http://127\.0\.0\.1:[0-9]+", url), "Smoke URLs must be loopback")
    evidence = ROOT / "target/verification/smoke.json"
    evidence.unlink(missing_ok=True)
    compose = ["docker", "compose", "--project-name", args.project, "--file", str(ROOT / "infra/smoke/compose.core.yaml")]

    def command(*arguments, input_text=None):
        result = subprocess.run([*compose, *arguments], input=input_text, capture_output=True, text=True, timeout=60)
        require(result.returncode == 0, "Smoke infrastructure command failed (output withheld)")
        return result.stdout.strip()

    def sql(query):
        return command("exec", "-T", "postgres", "psql", "-U", "postgres", "-d", "libra_core", "-At", "-v", "ON_ERROR_STOP=1", "-c", query)

    public = Browser(args.base_url)

    def healthy():
        try:
            data, _ = public.request("GET", "/api/core/actuator/health")
            return data.get("status") == "UP"
        except RuntimeError:
            return False

    wait_until(healthy, "Core did not become healthy through Nginx", 120)
    passed("Packaged Core health through repository Nginx ingress")
    expected = sorted((p.name.split("__")[0][1:] for p in (ROOT / "services/core/src/main/resources/db/migration").glob("V*__*.sql")),
                      key=lambda version: tuple(int(part) for part in version.split(".")))
    require(sql("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank").splitlines() == expected,
            "Fresh PostgreSQL did not apply every migration")
    owners = sql("SELECT datname || ':' || pg_get_userbyid(datdba) FROM pg_database WHERE datname IN ('libra_core','libra_media','libra_recommendation') ORDER BY datname")
    require(owners.splitlines() == ["libra_core:libra_core", "libra_media:libra_media", "libra_recommendation:libra_recommendation"], "Database ownership mismatch")
    passed("Fresh Flyway migrations and separate database owners")
    require(public.api("GET", "/catalog") == [], "Smoke database must start with an empty catalog")
    public.api("GET", "/me", status=401)
    public.request("GET", "/api/core/actuator/metrics", status=401)
    public.request("GET", "/api/core/internal/v1/media/bindings/" + str(uuid.uuid4()), status=404)
    public.api("POST", "/auth/register", body={}, csrf=False, status=403)
    passed("Anonymous, CSRF, metrics and public internal-route denial")

    viewer = Browser(args.base_url)
    email = "viewer-" + uuid.uuid4().hex + "@smoke.example.test"
    password = secrets.token_urlsafe(32)
    viewer.api("POST", "/auth/register", body={"email": email, "displayName": "Smoke viewer", "password": password}, status=202)
    _, headers = viewer.request("POST", "/api/core/v1/auth/login", body={"email": email, "password": password})
    auth_cookies = [value for value in headers.get_all("Set-Cookie", []) if value.startswith(("LIBRA_ACCESS=", "LIBRA_REFRESH="))]
    require(len(auth_cookies) == 2 and all("HttpOnly" in value and "SameSite=Lax" in value and "Path=/" in value for value in auth_cookies), "Identity cookie contract mismatch")
    require("no-store" in headers.get("Cache-Control", ""), "Authentication response must be no-store")
    require(viewer.api("GET", "/me")["emailVerified"] is False, "New user must be unverified")
    denied = viewer.api("POST", "/subscriptions/simulate", body={"plan": "PREMIUM_30_DAYS"}, headers={"Idempotency-Key": str(uuid.uuid4())}, status=403)
    require(denied["code"] == "EMAIL_VERIFICATION_REQUIRED", "Unverified Premium gate mismatch")
    require(sql("SELECT count(*) FROM subscription_purchases") == "0", "Denied purchase produced a write")
    passed("Registration/login cookies and unverified Premium denial without mutation")

    mail = Browser(args.mail_url)

    def verification_token():
        messages, _ = mail.request("GET", "/api/v1/messages")
        for message in messages.get("messages", []):
            if any(recipient.get("Address") == email for recipient in message.get("To", [])):
                body, _ = mail.request("GET", "/api/v1/message/" + message["ID"])
                match = re.search(r"token=([A-Za-z0-9_-]{43})", body.get("Text", ""))
                if match:
                    return match[1]
        return None

    token = wait_until(verification_token, "Durable mail worker did not deliver verification to Mailpit")
    viewer.api("POST", "/auth/verify-email", body={"token": token}, status=204)
    viewer.api("POST", "/auth/verify-email", body={"token": token}, status=400)
    require(viewer.api("GET", "/me")["emailVerified"] is True, "Verification did not update live account")
    passed("Scheduled encrypted mail queue to Mailpit and single-use email verification")

    key = {"Idempotency-Key": str(uuid.uuid4())}
    purchase = viewer.api("POST", "/subscriptions/simulate", body={"plan": "PREMIUM_30_DAYS"}, headers=key)
    repeat = viewer.api("POST", "/subscriptions/simulate", body={"plan": "PREMIUM_30_DAYS"}, headers=key)
    require(purchase == repeat and purchase["simulated"] is True, "Simulated purchase replay mismatch")
    require(len(viewer.api("GET", "/subscriptions/purchases")) == 1, "Replay duplicated purchase")
    require(viewer.api("GET", "/subscriptions")["status"] == "ACTIVE", "Premium status not active")
    profiles = viewer.api("GET", "/profiles")
    require(len(profiles) == 1, "Default profile missing")
    profile = viewer.api("POST", "/profiles", body={"name": "Smoke extra"}, status=201)
    recommendations = viewer.api("GET", "/profiles/" + profile["id"] + "/recommendations")
    require(recommendations["source"] == "FALLBACK" and recommendations["reason"] == "DISABLED", "Analytics fallback must be truthful")
    viewer.api("GET", "/admin/operations/summary", status=403)
    viewer.request("GET", "/api/core/actuator/metrics", status=403)
    passed("Simulated Premium idempotency, profile creation, fallback and USER denial")

    administrator = Browser(args.base_url)
    administrator.api("POST", "/auth/login", body={"email": "admin@smoke.example.test", "password": os.environ["LIBRA_SMOKE_ADMIN_PASSWORD"]})
    administrator.api("GET", "/admin/operations/summary")
    administrator.request("GET", "/api/core/actuator/metrics")
    stats = administrator.api("GET", "/admin/statistics?from=2026-01-01&to=2026-01-02")
    require(stats["status"] == "UNAVAILABLE" and stats["qualifiedViews"] is None, "Unavailable statistics cannot become zero")
    administrator.api("GET", "/profiles/" + profile["id"], status=404)
    passed("ADMIN operations/metrics, cross-account profile denial, unavailable statistics")

    movie = administrator.api("POST", "/admin/catalog", status=201, body={"kind": "MOVIE", "metadata": {
        "title": "Smoke movie", "description": "Synthetic Core contract fixture", "genres": ["drama"],
        "releaseYear": 2026, "language": "en", "credits": [], "imageReferences": [], "accessTier": "PREMIUM"}})
    content_id = movie["id"]
    admin_path = "/admin/catalog/" + content_id
    asset_id = str(uuid.uuid4())
    movie = administrator.api("POST", admin_path + "/media-bindings", body={"expectedVersion": movie["version"], "assetId": asset_id, "assetVersion": 1})
    administrator.api("POST", admin_path + "/publication", body={"expectedVersion": movie["version"]}, status=409)
    public.api("GET", "/catalog/" + content_id, status=404)
    media_event = {"eventId": str(uuid.uuid4()), "eventType": "MediaAssetStateChanged", "schemaVersion": 1,
                   "aggregateId": asset_id, "aggregateVersion": 1, "occurredAt": datetime.now(timezone.utc).isoformat(),
                   "correlationId": str(uuid.uuid4()), "payload": {"contentId": content_id,
                   "bindingId": movie["candidate"]["id"], "assetId": asset_id, "assetVersion": 1, "state": "READY", "durationSeconds": 120}}
    command("exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-producer.sh", "--bootstrap-server", "kafka:9092",
            "--topic", "media.assets.v1", "--property", "parse.key=true", "--property", "key.separator=|",
            input_text=asset_id + "|" + json.dumps(media_event) + "\n")

    def ready_movie():
        current = administrator.api("GET", admin_path)
        return current if current["candidate"]["state"] == "READY" else None

    movie = wait_until(ready_movie, "Synthetic Media READY was not projected by Core")
    movie = administrator.api("POST", admin_path + "/publication", body={"expectedVersion": movie["version"]})
    require(public.api("GET", "/catalog/" + content_id)["mediaReady"] is True, "Published READY movie not visible")
    passed("Non-READY publication denial, synthetic Kafka Media projection and explicit publication")

    watchlist_path = "/profiles/" + profile["id"] + "/watchlist"
    viewer.api("PUT", watchlist_path + "/" + content_id, status=204)
    viewer.api("PUT", watchlist_path + "/" + content_id, status=204)
    require(len(viewer.api("GET", watchlist_path)) == 1, "Watchlist retry duplicated entry")
    playback, ticket_headers = viewer.request("POST", "/api/core/v1/playback/sessions", status=201,
                                              body={"profileId": profile["id"], "contentId": content_id})
    session_path = "/playback/sessions/" + playback["sessionId"]
    cookie_path = "/api/media/v1/streams/" + playback["sessionId"] + "/"
    require(playback["manifestPath"].startswith(cookie_path) and "?" not in playback["manifestPath"], "Manifest must be credential-free")
    require(any("Path=" + cookie_path in cookie and "HttpOnly" in cookie and "SameSite=Lax" in cookie
                for cookie in ticket_headers.get_all("Set-Cookie", [])), "Media ticket cookie path/flags mismatch")
    progress = {"sequence": 1, "positionMs": 12000, "playedMs": 0, "state": "SEEKING"}
    require(viewer.api("POST", session_path + "/progress", body=progress)["accepted"] is True, "Progress was not accepted")
    require(viewer.api("POST", session_path + "/progress", body=progress)["accepted"] is False, "Duplicate progress accepted twice")
    history_path = "/profiles/" + profile["id"] + "/history"
    history = viewer.api("GET", history_path)
    require(len(history) == 1 and history[0]["positionMs"] == 12000, "Persisted resume mismatch")
    viewer.api("POST", session_path + "/renew")
    administrator.api("DELETE", admin_path + "/publication?expectedVersion=" + str(movie["version"]))
    public.api("GET", "/catalog/" + content_id, status=404)
    viewer.api("POST", session_path + "/renew", status=404)
    viewer.api("DELETE", history_path, status=204)
    require(viewer.api("GET", history_path) == [], "History clear did not persist")
    passed("Watchlist retry, playback ticket cookie, progress replay/history and unpublication renewal denial")

    # Correlate a real broker record with the committed outbox event, without
    # exposing its payload or credentials to CI artifacts.
    wait_until(lambda: int(sql("SELECT count(*) FROM outbox_events WHERE delivery_state = 'SENT'")) > 0,
               "Scheduled outbox did not receive broker acknowledgement")
    record = command("exec", "-T", "kafka", "/opt/kafka/bin/kafka-console-consumer.sh", "--bootstrap-server", "kafka:9092",
                     "--topic", "core.profiles.v1", "--from-beginning", "--max-messages", "1", "--timeout-ms", "20000")
    event = json.loads(record)
    event_id = str(uuid.UUID(event["eventId"]))
    require(sql("SELECT count(*) FROM outbox_events WHERE delivery_state = 'SENT' AND event_id = '" + event_id + "'") == "1", "Broker record does not match acknowledged outbox")
    passed("Scheduled outbox ACK and matching record read from real Kafka")
    viewer.api("POST", "/auth/refresh")
    viewer.api("GET", "/me")
    viewer.api("POST", "/auth/logout", status=204)
    viewer.api("GET", "/me", status=401)
    viewer.api("POST", "/auth/refresh", status=401)
    passed("Refresh rotation and logout revocation through ingress")
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text(json.dumps({"checks": CHECKS, "boundary": "Local disposable Core/PG/Kafka/Mailpit/Nginx; no Media HLS or Analytics projections"}, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Library exceptions may include URLs, tokens or SQL output; keep those
        # out of uploaded logs. Our own RuntimeError messages are fixed checks.
        print(f"FAIL: {error if isinstance(error, RuntimeError) else type(error).__name__}", flush=True)
        raise SystemExit(1) from None
