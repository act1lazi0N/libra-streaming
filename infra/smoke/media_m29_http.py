"""M29 end-to-end playback through the repository ingress. Never print credentials, tickets or bodies."""
import argparse
from datetime import datetime, timezone
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import time
import urllib.error
import urllib.request
import uuid

from core_http import require, wait_until

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "target/verification/m29"
CHECKS = []
SESSION = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
TICKET_COOKIE = "__Secure-LIBRA_PLAYBACK"


def passed(message):
    CHECKS.append(message)
    print(f"PASS: {message}", flush=True)


class LoopbackSecureContext(http.cookiejar.DefaultCookiePolicy):
    """Browsers treat http://127.0.0.1 and http://localhost as secure contexts and send Secure cookies there; the
    standard library does not. Every URL this smoke opens is asserted to be loopback."""

    def return_ok_secure(self, cookie, request):
        return True


class Viewer:
    def __init__(self, base):
        self.base = base
        self.cookies = http.cookiejar.CookieJar(LoopbackSecureContext())
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}),
                                                urllib.request.HTTPCookieProcessor(self.cookies))

    def open(self, method, path, body=None, headers=None, limit=16 * 1024 * 1024):
        """One request through the ingress. Returns (status, headers, raw body); never raises on an HTTP status."""
        data = None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
        request = urllib.request.Request(self.base + path, data=data, headers=dict(headers or {}), method=method)
        try:
            response = self.http.open(request, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read(limit + 1)
            require(len(raw) <= limit, "Unexpected oversized smoke response")
            return response.status, response.headers, raw

    def core(self, method, path, body=None, status=200, headers=None):
        headers = dict(headers or {})
        if method != "GET":
            _, _, raw = self.open("GET", "/api/core/v1/auth/csrf")
            token = json.loads(raw)
            headers[token["headerName"]] = token["token"]
        if body is not None:
            headers["Content-Type"] = "application/json"
        code, response_headers, raw = self.open(method, "/api/core/v1" + path, body, headers)
        require(code == status, f"{method} {path.split('?')[0]}: expected {status}, got {code}")
        value = json.loads(raw) if raw and "json" in response_headers.get("Content-Type", "") else None
        return value, response_headers

    def ticket(self, session_id):
        values = [c.value for c in self.cookies if c.name == TICKET_COOKIE
                  and c.path == f"/api/media/v1/streams/{session_id}/"]
        require(len(values) == 1, "Expected exactly one ticket cookie for the session")
        return values[0]


def problem(status, headers, raw, expected_status, code):
    require(status == expected_status, f"Expected {expected_status}, got {status}")
    require(headers.get("Content-Type", "").startswith("application/problem+json"), "Refusal is not a problem")
    require("no-store" in headers.get("Cache-Control", ""), "Refusal is cacheable")
    body = json.loads(raw)
    require(body.get("code") == code and re.fullmatch(SESSION, body.get("correlationId", "")), "Problem shape changed")
    text = raw.decode()
    for leaked in ("libra-hls", "libra-source", "hls/", "staging/", "sources/", "eyJ", "Exception"):
        require(leaked not in text, "A refusal reflected a storage or credential detail")
    return body


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--mail-url", required=True)
    parser.add_argument("--project", required=True)
    args = parser.parse_args()
    require(re.fullmatch(r"libra-m29-[a-f0-9]{12}", args.project), "Expected an isolated M29 project")
    for url in (args.base_url, args.mail_url):
        require(re.fullmatch(r"http://127\.0\.0\.1:[0-9]+", url), "Smoke URLs must be loopback")
    browser_endpoint = os.environ["MEDIA_S3_BROWSER_ENDPOINT"]
    require(re.fullmatch(r"http://127\.0\.0\.1:[0-9]+", browser_endpoint), "Storage endpoint must be loopback")
    evidence = ROOT / "target/verification/media-m29.json"
    evidence.unlink(missing_ok=True)
    shutil.rmtree(WORK, ignore_errors=True)
    WORK.mkdir(parents=True)
    compose = ["docker", "compose", "--project-name", args.project, "--profile", "full",
               "--env-file", os.environ["LIBRA_M29_ENV_FILE"], "--file", str(ROOT / "infra/compose.yaml"),
               "--file", str(ROOT / "infra/smoke/compose.media-m29.yaml")]

    def sql(database, query):
        require(database in ("libra_core", "libra_media"), "Unexpected database")
        result = subprocess.run([*compose, "exec", "-T", "postgres", "psql", "-U", "postgres", "-d", database,
                                 "-At", "-v", "ON_ERROR_STOP=1", "-c", query], capture_output=True, text=True,
                                timeout=30)
        require(result.returncode == 0, "Disposable database query failed (output withheld)")
        return result.stdout.strip()

    public = Viewer(args.base_url)

    def healthy(path):
        status, _, raw = public.open("GET", path)
        # Nginx answers 502 while an upstream is still starting.
        return status == 200 and json.loads(raw).get("status") == "UP"

    wait_until(lambda: healthy("/api/core/actuator/health"), "Core did not become healthy through Nginx", 180)
    wait_until(lambda: healthy("/api/media/actuator/health/readiness"), "Media did not become ready through Nginx", 180)
    passed("Packaged Core and Media healthy behind the repository Nginx ingress")

    # Administrator: catalog entry, upload intent, signed PUT, completion.
    admin = Viewer(args.base_url)
    admin.core("POST", "/auth/login", body={"email": "admin@m29.example.test",
                                            "password": os.environ["LIBRA_M29_ADMIN_PASSWORD"]})
    movie, _ = admin.core("POST", "/admin/catalog", status=201, body={"kind": "MOVIE", "metadata": {
        "title": "M29 playback fixture", "description": "Disposable end-to-end playback", "genres": ["drama"],
        "releaseYear": 2026, "language": "en", "credits": [], "imageReferences": [], "accessTier": "PREMIUM"}})
    content_id = movie["id"]
    clip_path = WORK / "source.mp4"
    encode = subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30",
                             "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000", "-t", "12",
                             "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-c:a", "aac",
                             "-b:a", "48k", "-shortest", "-movflags", "+faststart", str(clip_path)],
                            capture_output=True, timeout=120)
    require(encode.returncode == 0 and clip_path.is_file(), "Could not generate the source clip")
    clip = clip_path.read_bytes()
    created, _ = admin.core("POST", f"/admin/catalog/{content_id}/uploads", status=201, body={
        "requestId": str(uuid.uuid4()), "expectedVersion": movie["version"], "byteLength": len(clip),
        "sha256": hashlib.sha256(clip).hexdigest()})
    upload_id = str(uuid.UUID(created["uploadId"]))
    grant, _ = admin.core("POST", f"/admin/uploads/{upload_id}/upload-url")
    require(grant["method"] == "PUT" and grant["url"].startswith(browser_endpoint + "/libra-source/staging/"),
            "Grant did not target the browser staging endpoint")
    put = urllib.request.Request(grant["url"], data=clip, headers=grant["requiredHeaders"], method="PUT")
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(put, timeout=60) as response:
        require(response.status == 200, "Signed PUT to SeaweedFS failed")
    queued, _ = admin.core("POST", f"/admin/uploads/{upload_id}/complete", status=202)
    require(queued["assetState"] == "QUEUED", "Completion did not queue processing")
    passed("ADMIN upload through Core and Media control, signed PUT to private SeaweedFS, durable job queued")

    def media_ready():
        status, _ = admin.core("GET", f"/admin/uploads/{upload_id}")
        require(status.get("failureCode") is None, "Processing failed: " + str(status.get("failureCode")))
        return status if status["assetState"] == "READY" else None

    wait_until(media_ready, "Media worker did not make the upload READY", 300)

    def core_ready():
        current, _ = admin.core("GET", f"/admin/catalog/{content_id}")
        candidate = current.get("candidate") or {}
        return current if candidate.get("state") == "READY" else None

    movie = wait_until(core_ready, "Core did not project Media's READY event from Kafka", 120)
    # The broker acknowledged before Media marked the row, so the mark may trail Core's projection briefly.
    wait_until(lambda: sql("libra_media", "SELECT count(*) FROM media_outbox_events WHERE delivery_state = 'SENT' "
                                          "AND payload->>'state' = 'READY'") == "1",
               "Media outbox did not mark one READY event sent", 30)
    passed("Media worker probe/transcode/finalize to READY, outbox to real Kafka, Core projection of the candidate")

    movie, _ = admin.core("POST", f"/admin/catalog/{content_id}/publication", body={"expectedVersion": movie["version"]})
    catalog, _ = public.core("GET", f"/catalog/{content_id}")
    require(catalog["mediaReady"] is True, "Published content is not playable in the public catalog")
    passed("Explicit publication of the READY binding")

    def register(premium):
        viewer = Viewer(args.base_url)
        email = "viewer-" + uuid.uuid4().hex + "@m29.example.test"
        password = secrets.token_urlsafe(32)
        viewer.core("POST", "/auth/register", status=202,
                    body={"email": email, "displayName": "M29 viewer", "password": password})
        mail = Viewer(args.mail_url)

        def token():
            _, _, raw = mail.open("GET", "/api/v1/messages")
            for message in json.loads(raw).get("messages", []):
                if any(r.get("Address") == email for r in message.get("To", [])):
                    _, _, body = mail.open("GET", "/api/v1/message/" + message["ID"])
                    match = re.search(r"token=([A-Za-z0-9_-]{43})", json.loads(body).get("Text", ""))
                    if match:
                        return match[1]
            return None

        viewer.core("POST", "/auth/verify-email", status=204, body={"token": wait_until(token, "No verification mail")})
        viewer.core("POST", "/auth/login", body={"email": email, "password": password})
        if premium:
            viewer.core("POST", "/subscriptions/simulate", body={"plan": "PREMIUM_30_DAYS"},
                        headers={"Idempotency-Key": str(uuid.uuid4())})
        profiles, _ = viewer.core("GET", "/profiles")
        return viewer, profiles[0]["id"]

    viewer, profile = register(premium=True)
    session, headers = viewer.core("POST", "/playback/sessions", status=201,
                                   body={"profileId": profile, "contentId": content_id})
    session_id = session["sessionId"]
    require(re.fullmatch(SESSION, session_id), "Session identity is not a UUID")
    stream_path = f"/api/media/v1/streams/{session_id}/"
    require(session["manifestPath"] == stream_path + "master.m3u8", "Manifest path is not the session's master")
    set_cookies = [c for c in headers.get_all("Set-Cookie", []) if c.startswith(TICKET_COOKIE + "=")]
    require(len(set_cookies) == 1 and all(flag in set_cookies[0] for flag in
                                          ("Path=" + stream_path, "Secure", "HttpOnly", "SameSite=Lax")),
            "Ticket cookie name, scope or flags changed")
    ticket = viewer.ticket(session_id)
    passed("Verified Premium viewer admitted; Core's own signer set the __Secure- ticket on the session path")

    def stream(client, name, extra=None):
        return client.open("GET", stream_path + name, headers=extra)

    status, headers, master = stream(viewer, "master.m3u8")
    require(status == 200 and headers.get("Content-Type") == "application/vnd.apple.mpegurl"
            and headers.get("Cache-Control") == "no-store" and headers.get("X-Content-Type-Options") == "nosniff"
            and int(headers["Content-Length"]) == len(master) and "Set-Cookie" not in headers,
            "Master playlist headers changed through the ingress")
    references = [line for line in master.decode().splitlines() if line and not line.startswith("#")]
    require(references == ["rendition.m3u8"], "Master playlist does not name only the rendition")
    status, _, rendition = stream(viewer, "rendition.m3u8")
    require(status == 200, "Rendition playlist was not served")
    segments = [line for line in rendition.decode().splitlines() if line and not line.startswith("#")]
    require(segments and all(re.fullmatch(r"segment-[0-9]{5}\.ts", s) for s in segments),
            "Rendition names something outside the vocabulary")
    (WORK / "hls").mkdir()
    (WORK / "hls/master.m3u8").write_bytes(master)
    (WORK / "hls/rendition.m3u8").write_bytes(rendition)
    total = 0
    for name in segments:
        status, headers, body = stream(viewer, name)
        require(status == 200 and headers.get("Content-Type") == "video/mp2t"
                and int(headers["Content-Length"]) == len(body) and headers.get("Cache-Control") == "no-store",
                "Segment was not served whole through the ingress")
        (WORK / "hls" / name).write_bytes(body)
        total += len(body)
    passed(f"Master, rendition and all {len(segments)} segments ({total} bytes) served whole through Nginx")

    def decode(source, extra=()):
        result = subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-xerror", *extra, "-i", source,
                                 "-map", "0", "-f", "null", "-"], capture_output=True, timeout=180)
        require(result.returncode == 0 and not result.stderr.strip(), "HLS did not decode cleanly")

    decode(str(WORK / "hls/master.m3u8"))
    # ffmpeg as the player: it requests the master, the rendition and every segment through Nginx itself, with the
    # ticket as the only credential.
    decode(args.base_url + session["manifestPath"], ("-headers", f"Cookie: {TICKET_COOKIE}={ticket}\r\n"))
    probe = subprocess.run(["ffprobe", "-v", "error", "-headers", f"Cookie: {TICKET_COOKIE}={ticket}\r\n",
                            "-show_entries", "format=duration:stream=codec_type,codec_name,height", "-of", "json",
                            args.base_url + session["manifestPath"]], capture_output=True, text=True, timeout=120)
    require(probe.returncode == 0, "ffprobe could not read the HLS through Nginx")
    measured = json.loads(probe.stdout)
    kinds = sorted((s["codec_type"], s["codec_name"]) for s in measured["streams"])
    require(kinds == [("audio", "aac"), ("video", "h264")], "Delivered streams changed")
    require(abs(float(measured["format"]["duration"]) - 12.0) < 0.5, "Delivered duration differs from the source")
    require(all(s.get("height", 0) <= 720 for s in measured["streams"]), "Rendition exceeds 720p")
    passed("ffmpeg decoded the downloaded HLS and played it directly through Nginx with only the ticket cookie "
           "(H.264 + AAC, 12 s)")

    # Refusals through the ingress, each beside the allowed request above.
    anonymous = Viewer(args.base_url)
    for name in ("master.m3u8", "rendition.m3u8", segments[0]):
        problem(*stream(anonymous, name), 401, "AUTHENTICATION_REQUIRED")
        problem(*stream(anonymous, name, {"Authorization": "Bearer " + ticket}), 401, "AUTHENTICATION_REQUIRED")
        problem(*stream(anonymous, name, {"Cookie": "LIBRA_PLAYBACK=" + ticket}), 401, "AUTHENTICATION_REQUIRED")
    other, other_profile = register(premium=False)
    denied, _ = other.core("POST", "/playback/sessions", status=403,
                           body={"profileId": other_profile, "contentId": content_id})
    require(denied["code"] == "PREMIUM_REQUIRED", "Non-Premium admission refusal changed")
    require(not [c for c in other.cookies if c.name == TICKET_COOKIE], "A refused admission set a ticket")
    # Identity cookies reach the stream path (Path=/) but never authorize it.
    problem(*stream(other, "master.m3u8"), 401, "AUTHENTICATION_REQUIRED")
    second, _ = viewer.core("POST", "/playback/sessions", status=201,
                            body={"profileId": profile, "contentId": content_id})
    elsewhere = f"/api/media/v1/streams/{second['sessionId']}/master.m3u8"
    problem(*anonymous.open("GET", elsewhere, headers={"Cookie": f"{TICKET_COOKIE}={ticket}"}), 403, "ACCESS_DENIED")
    passed("No cookie, bearer header, wrong cookie name, identity cookies alone and another session's ticket refused; "
           "non-Premium admission refused without a ticket")

    for path in (stream_path + "source.mp4", stream_path + "segment-0001.ts", stream_path + "%2e%2e/master.m3u8",
                 stream_path + "rendition.m3u8/x", "/api/media/v1/streams/not-a-session/master.m3u8",
                 "/api/media/v1/streams/" + session_id.upper() + "/master.m3u8"):
        body = problem(*viewer.open("GET", path), 404, "NOT_FOUND")
        require("instance" not in body, "A malformed stream path reached Media")
    for path in ("/api/media/internal/v1/uploads", "/libra-hls/hls/", "/libra-source/staging/", "/s3/libra-hls"):
        status, _, _ = viewer.open("GET", path)
        require(status == 404, "Internal or storage path reachable through the ingress")
    passed("Malformed stream paths answered 404 by Nginx without reaching Media; internal and storage paths 404")

    renewed, headers = viewer.core("POST", f"/playback/sessions/{session_id}/renew")
    require(any(c.startswith(TICKET_COOKIE + "=") and "Path=" + stream_path in c
                for c in headers.get_all("Set-Cookie", [])), "Renewal did not reissue the ticket")
    require(viewer.ticket(session_id) != ticket, "Renewal reused the old ticket")
    require(stream(viewer, segments[-1])[0] == 200, "Renewed ticket does not play")
    passed("Renewal reissued the ticket cookie and the renewed ticket plays")

    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text(json.dumps({
        "result": "MEDIA_M29_SMOKE_PASS", "checkedAt": datetime.now(timezone.utc).isoformat(), "checks": CHECKS,
        "segments": len(segments), "deliveredBytes": total,
        "boundary": "Local disposable Compose: real compose.yaml and nginx.conf, packaged Core and Media JARs, "
                    "PostgreSQL, Kafka, Mailpit, SeaweedFS; ffmpeg as the player; no browser, web app or analytics"
    }, indent=2) + "\n", encoding="utf-8")
    print("MEDIA_M29_SMOKE_PASS", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Library exceptions may include URLs, tickets or SQL output; only our fixed RuntimeError messages print.
        print(f"FAIL: {error if isinstance(error, RuntimeError) else type(error).__name__}", flush=True)
        raise SystemExit(1) from None
