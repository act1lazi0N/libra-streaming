"""M34 fault matrix of the whole slice through the repository ingress: Core, Media, Kafka, storage and PostgreSQL are
interrupted one at a time at chosen points and restored, and every row checks the persistent state it should converge
to. Never print credentials, tickets, signed URLs or bodies."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid

from core_http import require, wait_until
import media_m29_http as m29
from media_m29_http import ROOT, SESSION, TICKET_COOKIE, Viewer, passed, problem

WORK = ROOT / "target/verification/m34"
ROWS = []
FINDINGS = []


def expect(condition, message):
    """A matrix expectation that is recorded instead of stopping the run, so later rows still execute."""
    if not condition:
        FINDINGS.append(message)
        print(f"FINDING: {message}", flush=True)


def arguments():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--mail-url", required=True)
    parser.add_argument("--project", required=True)
    args = parser.parse_args()
    require(re.fullmatch(r"libra-m34-[a-f0-9]{12}", args.project), "Expected an isolated M34 project")
    for url in (args.base_url, args.mail_url):
        require(re.fullmatch(r"http://127\.0\.0\.1:[0-9]+", url), "Smoke URLs must be loopback")
    return args


class Stack:
    """The disposable project's Compose services, its two databases and the ingress."""

    def __init__(self, args):
        self.args = args
        self.compose = ["docker", "compose", "--project-name", args.project, "--profile", "full",
                        "--env-file", os.environ["LIBRA_M29_ENV_FILE"], "--file", str(ROOT / "infra/compose.yaml"),
                        "--file", str(ROOT / "infra/smoke/compose.media-m29.yaml")]
        self.public = Viewer(args.base_url)

    def docker(self, *command, timeout=180):
        result = subprocess.run([*self.compose, *command], capture_output=True, text=True, timeout=timeout)
        require(result.returncode == 0, f"Compose {command[0]} failed (output withheld)")
        return result.stdout

    def sql(self, database, query):
        require(database in ("libra_core", "libra_media"), "Unexpected database")
        result = subprocess.run([*self.compose, "exec", "-T", "postgres", "psql", "-U", "postgres", "-d", database,
                                 "-At", "-v", "ON_ERROR_STOP=1", "-c", query], capture_output=True, text=True,
                                timeout=30)
        require(result.returncode == 0, "Disposable database query failed (output withheld)")
        return result.stdout.strip()

    def healthy(self, path):
        try:
            status, _, raw = self.public.open("GET", path)
            return status == 200 and json.loads(raw).get("status") == "UP"
        except (OSError, ValueError):
            return False

    def wait_services(self, seconds=180):
        wait_until(lambda: self.healthy("/api/core/actuator/health"), "Core did not become healthy", seconds)
        wait_until(lambda: self.healthy("/api/media/actuator/health/readiness"), "Media did not become ready", seconds)

    def stop(self, service):
        self.docker("stop", "--timeout", "10", service)

    def kill(self, service):
        self.docker("kill", "--signal", "SIGKILL", service)

    def start(self, service):
        self.docker("up", "-d", "--no-deps", "--no-recreate", "--wait", "--wait-timeout", "180", service,
                    timeout=240)
        self.wait_services()


class Client(Viewer):
    """A Viewer whose ten-minute access token is refreshed through Core before it can lapse during the matrix."""

    def __init__(self, base):
        super().__init__(base)
        self.refreshed = None
        # Two refreshes of one token are a replay, which Core answers by revoking the session.
        self.refreshing = threading.Lock()

    def touch(self, force=False):
        with self.refreshing:
            if self.refreshed is not None and (force or time.monotonic() - self.refreshed > 240):
                self.refreshed = None
                super().core("POST", "/auth/refresh")
                self.refreshed = time.monotonic()

    def core(self, method, path, body=None, status=200, headers=None):
        self.touch()
        value = super().core(method, path, body, status, headers)
        if path == "/auth/login":
            self.refreshed = time.monotonic()
        return value


def row(identifier, injection, expected, recovery, observed):
    ROWS.append({"row": identifier, "injection": injection, "expectedState": expected, "recovery": recovery,
                 "observed": observed})
    passed(f"{identifier}: {observed}")


def main():
    args = arguments()
    evidence = ROOT / "target/verification/media-m34.json"
    evidence.unlink(missing_ok=True)
    browser_endpoint = os.environ["MEDIA_S3_BROWSER_ENDPOINT"]
    require(re.fullmatch(r"http://127\.0\.0\.1:[0-9]+", browser_endpoint), "Storage endpoint must be loopback")
    shutil.rmtree(WORK, ignore_errors=True)
    WORK.mkdir(parents=True)
    stack = Stack(args)
    stack.wait_services()
    passed("Packaged Core and Media healthy behind the repository Nginx ingress")

    clip_path = WORK / "source.mp4"
    encode = subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi", "-i",
                             "testsrc2=size=1280x720:rate=30", "-f", "lavfi", "-i",
                             "sine=frequency=440:sample_rate=48000", "-t", "20", "-c:v", "libx264", "-profile:v",
                             "high", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "48k", "-shortest", "-movflags",
                             "+faststart", str(clip_path)], capture_output=True, timeout=180)
    require(encode.returncode == 0 and clip_path.is_file(), "Could not generate the source clip")
    clip = clip_path.read_bytes()
    digest = hashlib.sha256(clip).hexdigest()

    def login_admin():
        admin = Client(args.base_url)
        admin.core("POST", "/auth/login", body={"email": "admin@m29.example.test",
                                                "password": os.environ["LIBRA_M29_ADMIN_PASSWORD"]})
        return admin

    admin = login_admin()

    def catalog(title):
        movie, _ = admin.core("POST", "/admin/catalog", status=201, body={"kind": "MOVIE", "metadata": {
            "title": title, "description": "Disposable M34 fault fixture", "genres": ["drama"],
            "releaseYear": 2026, "language": "en", "credits": [], "imageReferences": [], "accessTier": "PREMIUM"}})
        return movie

    def intent(movie, request_id, status=201):
        body = {"requestId": request_id, "expectedVersion": movie["version"], "byteLength": len(clip),
                "sha256": digest}
        return admin.core("POST", f"/admin/catalog/{movie['id']}/uploads", status=status, body=body)[0]

    def put(upload_id, data=clip):
        grant, _ = admin.core("POST", f"/admin/uploads/{upload_id}/upload-url")
        require(grant["method"] == "PUT" and grant["url"].startswith(browser_endpoint + "/libra-source/staging/"),
                "Grant did not target the browser staging endpoint")
        request = urllib.request.Request(grant["url"], data=data, headers=grant["requiredHeaders"], method="PUT")
        try:
            with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=60) as reply:
                return reply.status
        except urllib.error.HTTPError as error:
            with error:
                return error.status

    def submitted(title):
        """A catalog entry whose source is in staging and whose completion is queued."""
        movie = catalog(title)
        created = intent(movie, str(uuid.uuid4()))
        upload_id = str(uuid.UUID(created["uploadId"]))
        require(put(upload_id) == 200, "Signed PUT to SeaweedFS failed")
        queued, _ = admin.core("POST", f"/admin/uploads/{upload_id}/complete", status=202)
        require(queued["assetState"] == "QUEUED", "Completion did not queue processing")
        return movie, upload_id, queued["jobId"]

    def upload_status(upload_id):
        return admin.core("GET", f"/admin/uploads/{upload_id}")[0]

    def media_ready(upload_id, seconds=300):
        def ready():
            status = upload_status(upload_id)
            require(status.get("failureCode") is None, "Processing failed: " + str(status.get("failureCode")))
            return status if status["assetState"] == "READY" else None
        return wait_until(ready, "Media worker did not make the upload READY", seconds)

    def candidate(content_id):
        return (admin.core("GET", f"/admin/catalog/{content_id}")[0].get("candidate") or {})

    def core_ready(content_id, seconds=120):
        wait_until(lambda: candidate(content_id).get("state") == "READY",
                   "Core did not project Media's READY event", seconds)
        return admin.core("GET", f"/admin/catalog/{content_id}")[0]

    def job(job_id):
        stage, attempts = stack.sql("libra_media", f"SELECT stage, attempt_count FROM media_jobs "
                                                   f"WHERE id = '{uuid.UUID(job_id)}'").split("|")
        return stage, int(attempts)

    def ready_events(asset):
        return stack.sql("libra_media", "SELECT count(*) FROM media_outbox_events WHERE payload->>'state' = 'READY' "
                                        f"AND payload->>'assetId' = '{uuid.UUID(asset)}'")

    def selected(asset):
        return stack.sql("libra_media", f"SELECT selected_source_key, output_prefix, master_manifest_key "
                                        f"FROM media_assets WHERE asset_id = '{uuid.UUID(asset)}'")

    def publish(content_id):
        current = core_ready(content_id)
        admin.core("POST", f"/admin/catalog/{content_id}/publication", body={"expectedVersion": current["version"]})

    viewer, profile = m29_register(args, premium=True)

    def admit(content_id, client=None, profile_id=None):
        client = client or viewer
        session, _ = client.core("POST", "/playback/sessions", status=201,
                                 body={"profileId": profile_id or profile, "contentId": content_id})
        require(re.fullmatch(SESSION, session["sessionId"]), "Session identity is not a UUID")
        return session

    def decode(session, client=None):
        """ffmpeg as the player: every playlist and segment through Nginx with only the ticket cookie."""
        ticket = (client or viewer).ticket(session["sessionId"])
        result = subprocess.run(["ffmpeg", "-v", "error", "-nostdin", "-xerror", "-headers",
                                 f"Cookie: {TICKET_COOKIE}={ticket}\r\n", "-i",
                                 args.base_url + session["manifestPath"], "-map", "0", "-f", "null", "-"],
                                capture_output=True, timeout=180)
        require(result.returncode == 0 and not result.stderr.strip(), "HLS did not decode cleanly through Nginx")

    def stream(session, name, client=None):
        return (client or viewer).open("GET", f"/api/media/v1/streams/{session['sessionId']}/{name}")

    # A published baseline for the playback rows.
    base_movie, base_upload, _ = submitted("M34 baseline")
    base_status = media_ready(base_upload)
    publish(base_movie["id"])
    base_session = admit(base_movie["id"])
    decode(base_session)
    _, _, rendition = stream(base_session, "rendition.m3u8")
    segments = [line for line in rendition.decode().splitlines() if line and not line.startswith("#")]
    require(len(segments) >= 4, "Expected several segments")
    _, _, reference = stream(base_session, segments[1])
    reference_digest = hashlib.sha256(reference).hexdigest()
    passed(f"Baseline slice READY, published and decoded through Nginx ({len(segments)} segments)")

    # F1. Media down during provisioning: Core keeps its committed intent, answers a controlled 503, and the same
    # request identity provisions exactly one Media upload once Media is back.
    movie = catalog("M34 provisioning outage")
    request_id = str(uuid.uuid4())
    stack.stop("media")
    status, headers, raw = core_post(admin, f"/admin/catalog/{movie['id']}/uploads", {
        "requestId": request_id, "expectedVersion": movie["version"], "byteLength": len(clip), "sha256": digest})
    body = problem(status, headers, raw, 503, "MEDIA_UNAVAILABLE")
    intents = stack.sql("libra_core", f"SELECT count(*) FROM catalog_upload_intents WHERE request_id = '{request_id}'")
    require(intents == "1", "Core did not keep the committed intent")
    stack.start("media")
    retried = intent(movie, request_id, status=200)
    upload_id = str(uuid.UUID(retried["uploadId"]))
    require(retried["uploadState"] == "OPEN" and retried["assetState"] == "UPLOADING", "Retry did not provision")
    require(stack.sql("libra_media", f"SELECT count(*) FROM media_uploads WHERE id = '{upload_id}'") == "1",
            "Media did not hold exactly one upload")
    require(put(upload_id) == 200, "Signed PUT after the outage failed")
    stack.stop("media")
    status, headers, raw = core_post(admin, f"/admin/uploads/{upload_id}/complete", None)
    problem(status, headers, raw, 503, "MEDIA_UNAVAILABLE")
    stack.start("media")
    first, _ = admin.core("POST", f"/admin/uploads/{upload_id}/complete", status=202)
    again, _ = admin.core("POST", f"/admin/uploads/{upload_id}/complete", status=202)
    require(first["jobId"] == again["jobId"], "A repeated completion changed the job")
    require(stack.sql("libra_media", f"SELECT count(*) FROM media_jobs WHERE upload_id = '{upload_id}'") == "1",
            "Duplicate logical job")
    media_ready(upload_id)
    core_ready(movie["id"])
    row("F1 Media down at provisioning and completion",
        "docker stop media before POST .../uploads, again before POST .../complete",
        "Core intent committed, no Media upload or job; 503 MEDIA_UNAVAILABLE problem",
        "start Media; repeat the same requestId and completion",
        f"503 {body['code']} twice; retry 200 with one Media upload, one job, READY projected")

    # F2. Storage down at completion: Media cannot verify the staged source, nothing is queued, and completion
    # succeeds once storage is back.
    movie = catalog("M34 storage outage at completion")
    upload_id = str(uuid.UUID(intent(movie, str(uuid.uuid4()))["uploadId"]))
    require(put(upload_id) == 200, "Signed PUT failed")
    stack.stop("s3-gateway")
    status, headers, raw = core_post(admin, f"/admin/uploads/{upload_id}/complete", None)
    completion_refusal = status
    jobs = stack.sql("libra_media", f"SELECT count(*) FROM media_jobs WHERE upload_id = '{upload_id}'")
    media_health = stack.healthy("/api/media/actuator/health/readiness")
    stack.start("s3-gateway")
    if status == 202:
        # Completion only records the submission; the worker meets the outage instead and must still converge.
        pass
    else:
        problem(status, headers, raw, 503, "MEDIA_UNAVAILABLE")
        require(jobs == "0", "A refused completion queued a job")
        admin.core("POST", f"/admin/uploads/{upload_id}/complete", status=202)
    media_ready(upload_id)
    core_ready(movie["id"])
    row("F2 Storage down at completion", "docker stop s3-gateway before POST .../complete",
        "no job unless the staged source was verified; readiness reports storage DOWN",
        "start s3-gateway; repeat completion",
        f"completion {completion_refusal} with {jobs} job(s) while down; Media readiness "
        f"{'UP' if media_health else 'DOWN'} while down; READY after restore")

    # F3. Media killed while FFmpeg writes output: the lease expires, the job is reclaimed and READY exactly once.
    movie, upload_id, job_id = submitted("M34 crash in transcoding")
    wait_until(lambda: job(job_id)[0] == "TRANSCODING", "Job never reached TRANSCODING", 120)
    stack.kill("media")
    stage_at_crash, attempts_at_crash = job(job_id)
    stack.start("media")
    status = media_ready(upload_id, 300)
    core_ready(movie["id"])
    stage, attempts = job(job_id)
    require(ready_events(status["assetId"]) == "1", "READY was recorded other than once")
    publish(movie["id"])
    decode(admit(movie["id"]))
    row("F3 Media SIGKILL during TRANSCODING", "docker kill media while the job is TRANSCODING",
        f"job {stage_at_crash} with attempt {attempts_at_crash} and an expiring lease; no selected output",
        "start Media; the lease expires and the job is reclaimed",
        f"job {stage} after attempt {attempts}; one READY event; published HLS decodes")

    # F4. Media killed right after completion, before the source is frozen.
    movie, upload_id, job_id = submitted("M34 crash before freezing")
    stack.kill("media")
    stage_at_crash, _ = job(job_id)
    stack.start("media")
    status = media_ready(upload_id, 300)
    core_ready(movie["id"])
    require(ready_events(status["assetId"]) == "1", "READY was recorded other than once")
    source = stack.sql("libra_media", f"SELECT count(DISTINCT selected_source_key) FROM media_assets "
                                      f"WHERE asset_id = '{uuid.UUID(status['assetId'])}'")
    row("F4 Media SIGKILL right after completion", "docker kill media immediately after 202 QUEUED",
        f"job {stage_at_crash}; staged source retained", "start Media",
        f"READY once with {source} selected source; Core projection READY")

    # F5. A stale worker resumes after its lease was reclaimed by another instance.
    movie, upload_id, job_id = submitted("M34 stale worker")
    wait_until(lambda: job(job_id)[0] == "TRANSCODING", "Job never reached TRANSCODING", 120)
    stale = stack.docker("ps", "-q", "media").split()
    require(len(stale) == 1, "Expected one Media instance")
    stack.docker("pause", "media")
    paused, successor = True, None
    try:
        # A second instance of the same service, without the ingress alias, takes over once the lease expires.
        successor = stack.docker("run", "-d", "--no-deps", "media", timeout=240).split()[-1]
        # Core reads upload status through the paused instance (Media's control alias), so watch the database.
        wait_until(lambda: job(job_id)[0] == "SUCCEEDED", "The successor did not finish the reclaimed job", 300)
        _, attempts = job(job_id)
        require(attempts >= 2, "The job was not reclaimed")
        status = {"assetId": stack.sql("libra_media", "SELECT u.asset_id FROM media_jobs j JOIN media_uploads u ON "
                                                      f"u.id = j.upload_id WHERE j.id = '{uuid.UUID(job_id)}'")}
        before = selected(status["assetId"])
        # The stale worker's encoder is frozen with its instance; it must end before the selection is compared.
        frozen = "ffmpeg" in stack.docker("top", "media")
        stack.docker("unpause", "media")
        paused = False
        resumed_at = time.monotonic()
        wait_until(lambda: "ffmpeg" not in stack.docker("top", "media"), "The stale encoder never ended", 240)
        ended = time.monotonic() - resumed_at
        time.sleep(12)  # More than two heartbeat intervals for any write the stale worker attempts afterwards.
        after = selected(status["assetId"])
        require(before == after, "The stale worker changed the selected output")
        require(ready_events(status["assetId"]) == "1", "The stale worker recorded READY again")
        stage, final_attempts = job(job_id)
        require(stage == "SUCCEEDED" and final_attempts == attempts, "The stale worker changed the job")
    finally:
        if paused:
            subprocess.run([*stack.compose, "unpause", "media"], capture_output=True, timeout=60)
        if successor:
            subprocess.run(["docker", "rm", "-f", successor], capture_output=True, timeout=60)
    core_ready(movie["id"])
    publish(movie["id"])
    decode(admit(movie["id"]))
    stack.wait_services()
    row("F5 Stale worker resurrection", "docker pause media in TRANSCODING; second Media instance reclaims; unpause",
        "reclaimed job finalized once by the new lease holder", "unpause the old instance",
        f"stale encoder {'frozen while paused' if frozen else 'absent while paused'}, gone {ended:.0f} s after "
        "unpause; selected output "
        f"unchanged, one READY event, job SUCCEEDED after attempt {attempts}; HLS decodes")

    # F6. Kafka down when READY is recorded: the event waits in Media's outbox and Core's candidate stays unready.
    stack.stop("kafka")
    movie, upload_id, job_id = submitted("M34 broker outage")
    status = media_ready(upload_id, 300)
    asset = status["assetId"]
    # Events of one asset leave in version order, so the oldest unsent one is retried and READY waits behind it.
    wait_until(lambda: stack.sql("libra_media", "SELECT count(*) FROM media_outbox_events WHERE delivery_state <> "
                                                "'SENT' AND last_error_code = 'BROKER_UNAVAILABLE' AND asset_id = "
                                                f"'{uuid.UUID(asset)}'") == "1",
               "No event of the asset was retried while the broker was down", 120)
    pending = stack.sql("libra_media", "SELECT string_agg(payload->>'state' || ' ' || delivery_state || ' ' || "
                                       "coalesce(last_error_code, '-'), ', ' ORDER BY aggregate_version) FROM "
                                       f"media_outbox_events WHERE asset_id = '{uuid.UUID(asset)}'")
    require(stack.sql("libra_media", "SELECT count(*) FROM media_outbox_events WHERE delivery_state = 'SENT' AND "
                                     f"asset_id = '{uuid.UUID(asset)}'") == "0", "An event left without the broker")
    require(candidate(movie["id"]).get("state") != "READY", "Core projected READY without the broker")
    stack.start("kafka")
    core_ready(movie["id"], 420)
    wait_until(lambda: stack.sql("libra_media", "SELECT count(*) FROM media_outbox_events WHERE delivery_state = "
                                                f"'SENT' AND payload->>'assetId' = '{uuid.UUID(asset)}' AND "
                                                "payload->>'state' = 'READY'") == "1",
               "READY was not marked sent once", 60)
    row("F6 Kafka down at event delivery", "docker stop kafka before completion",
        f"READY committed in Media, outbox {pending}, Core candidate not READY", "start Kafka",
        "one READY event SENT; Core candidate READY")

    # F7. Core down while the event is delivered: the consumer resumes from its committed offset.
    movie, upload_id, job_id = submitted("M34 Core outage at projection")
    stack.stop("core")
    wait_until(lambda: stack.sql("libra_media", "SELECT count(*) FROM media_jobs WHERE id = "
                                                f"'{uuid.UUID(job_id)}' AND stage = 'SUCCEEDED'") == "1",
               "Media did not reach READY while Core was down", 300)
    wait_until(lambda: stack.sql("libra_media", "SELECT count(*) FROM media_outbox_events e JOIN media_uploads u ON "
                                                "u.asset_id = e.asset_id JOIN media_jobs j ON j.upload_id = u.id "
                                                "WHERE j.id = "
                                                f"'{uuid.UUID(job_id)}' AND e.delivery_state = 'SENT' AND "
                                                "e.payload->>'state' = 'READY'") == "1",
               "Media did not publish READY while Core was down", 120)
    stack.start("core")
    core_ready(movie["id"])
    receipts = stack.sql("libra_core", "SELECT count(*) FROM media_projection_receipts r JOIN "
                                       "catalog_media_bindings b ON b.id = r.binding_id WHERE b.content_id = "
                                       f"'{uuid.UUID(movie['id'])}'")
    row("F7 Core down at projection", "docker stop core after 202 QUEUED",
        "Media READY and SENT; Core offset uncommitted", "start Core",
        f"candidate READY with {receipts} projection receipt(s)")

    # F8. Core down during playback: Media verifies the ticket alone; renewal waits for Core.
    session = admit(base_movie["id"])
    stack.stop("core")
    status, _, body = stream(session, segments[1])
    require(status == 200 and hashlib.sha256(body).hexdigest() == reference_digest,
            "An issued ticket stopped playing while Core was down")
    started = time.monotonic()
    try:
        status, headers, raw = viewer.open("POST", f"/api/core/v1/playback/sessions/{session['sessionId']}/renew")
        kind = "problem" if headers.get("Content-Type", "").startswith("application/problem+json") else "page"
        renew_down = f"{status} {json.loads(raw).get('code') if kind == 'problem' else 'non-problem page'}"
    except TimeoutError:
        status, kind, renew_down = None, None, "no answer within the client's 30 s"
    waited = time.monotonic() - started
    expect(status == 503 and kind == "problem" and waited < 10,
           f"H3: renewal with Core stopped answered {renew_down} after {waited:.0f} s")
    if kind == "problem":
        problem(status, headers, raw, 503, "CORE_UNAVAILABLE")
    stack.start("core")
    viewer.core("POST", f"/playback/sessions/{session['sessionId']}/renew")
    decode(session)
    row("F8 Core down during playback", "docker stop core with an admitted session",
        "issued ticket keeps its bounded lifetime; no renewal", "start Core; renew",
        f"segment 200 byte-identical while down; renewal {renew_down} in {waited:.1f} s while down, 200 "
        "after; HLS decodes")

    # F9. Storage down during playback: a controlled 503 problem, never partial or unrelated bytes.
    session = admit(base_movie["id"])
    stack.stop("s3-gateway")
    status, headers, raw = stream(session, segments[1])
    storage_down = problem(status, headers, raw, 503, "MEDIA_UNAVAILABLE")["code"]
    stack.start("s3-gateway")
    status, _, body = stream(session, segments[1])
    require(status == 200 and hashlib.sha256(body).hexdigest() == reference_digest, "Segment changed after restore")
    row("F9 Storage down during playback", "docker stop s3-gateway with an admitted session",
        "no bytes served", "start s3-gateway", f"503 {storage_down} problem while down; identical 200 after")

    # F10. Media down during playback: the ingress answers its own no-store 503 problem.
    session = admit(base_movie["id"])
    stack.stop("media")
    status, headers, raw = stream(session, "master.m3u8")
    media_down = problem(status, headers, raw, 503, "MEDIA_UNAVAILABLE")["code"]
    stack.start("media")
    decode(session)
    row("F10 Media down during playback", "docker stop media with an admitted session", "no bytes served",
        "start Media", f"Nginx 503 {media_down} problem while down; HLS decodes after")

    # F11. PostgreSQL restarted: both services reconnect without a restart of their own.
    stack.docker("restart", "--timeout", "10", "postgres", timeout=120)
    stack.wait_services()
    session = admit(base_movie["id"])
    decode(session)
    admin.core("GET", "/me")
    row("F11 PostgreSQL restart", "docker restart postgres", "services keep running; pooled connections fail",
        "none (connection pools reconnect)", "admission, playback and administration work after the restart")

    # F12. Simultaneous completions and admissions.
    movie = catalog("M34 simultaneous completion")
    upload_id = str(uuid.UUID(intent(movie, str(uuid.uuid4()))["uploadId"]))
    require(put(upload_id) == 200, "Signed PUT failed")
    admins = [login_admin() for _ in range(4)]
    with ThreadPoolExecutor(4) as pool:
        results = list(pool.map(lambda a: core_post(a, f"/admin/uploads/{upload_id}/complete", None), admins))
    codes = sorted(r[0] for r in results)
    accepted = {json.loads(r[2])["jobId"] for r in results if r[0] == 202}
    require(len(accepted) == 1 and all(c in (202, 409) for c in codes), "Concurrent completions diverged")
    require(stack.sql("libra_media", f"SELECT count(*) FROM media_jobs WHERE upload_id = '{upload_id}'") == "1",
            "Duplicate logical job")
    media_ready(upload_id)
    viewer.touch(force=True)
    # One CSRF token for all of them: each fetch rotates the cookie the others' headers must match.
    _, _, raw = viewer.open("GET", "/api/core/v1/auth/csrf")
    csrf = json.loads(raw)
    with ThreadPoolExecutor(6) as pool:
        admitted = list(pool.map(lambda _: core_post(viewer, "/playback/sessions",
                                                     {"profileId": profile, "contentId": base_movie["id"]},
                                                     csrf=csrf), range(6)))
    admission_codes = sorted(f"{r[0]}" if r[0] == 201 else f"{r[0]} {json.loads(r[2]).get('code')}"
                             for r in admitted)
    sessions = {json.loads(r[2])["sessionId"] for r in admitted if r[0] == 201}
    require(len(sessions) == admission_codes.count("201") == 6, "Concurrent admissions were refused or shared a session")
    row("F12 Simultaneous completions and admissions", "4 concurrent completions; 6 concurrent admissions",
        "one job per upload; one session per admission", "none",
        f"completions {codes} with one job; admissions {admission_codes} with distinct sessions")

    # F13. Revocation: Core authority is current, an issued ticket keeps only its bounded lifetime.
    revoked, revoked_profile = m29_register(args, premium=True)
    session = admit(base_movie["id"], revoked, revoked_profile)
    access = [c for c in revoked.cookies if c.name.endswith("LIBRA_ACCESS")]
    require(len(access) == 1, "Expected one access cookie")
    stolen = f"{access[0].name}={access[0].value}"
    revoked.core("POST", "/auth/logout-all", status=204)
    replay = Viewer(args.base_url)
    for path, body in ((f"/playback/sessions/{session['sessionId']}/renew", None),
                       ("/playback/sessions", {"profileId": revoked_profile, "contentId": base_movie["id"]})):
        status, headers, raw = core_post(replay, path, body, extra={"Cookie": stolen})
        problem(status, headers, raw, 401, "AUTHENTICATION_REQUIRED")
    expiry = [c.expires for c in revoked.cookies if c.name == TICKET_COOKIE]
    require(len(expiry) == 1 and expiry[0] - time.time() <= 300, "Ticket lifetime exceeds five minutes")
    status, _, body = stream(session, segments[1], revoked)
    require(status == 200, "The issued ticket did not keep its bounded lifetime")
    suspended, suspended_profile = m29_register(args, premium=True)
    me, _ = suspended.core("GET", "/me")
    suspended_session = admit(base_movie["id"], suspended, suspended_profile)
    admin.core("POST", f"/admin/users/{me['id']}/suspension", status=204)
    status, headers, raw = core_post(suspended, f"/playback/sessions/{suspended_session['sessionId']}/renew", None)
    problem(status, headers, raw, 401, "AUTHENTICATION_REQUIRED")
    admin_access = [c for c in admin.cookies if c.name.endswith("LIBRA_ACCESS")]
    stolen_admin = f"{admin_access[0].name}={admin_access[0].value}"
    admin.core("POST", "/auth/logout", status=204)
    status, headers, raw = core_post(replay, "/admin/catalog", {"kind": "MOVIE", "metadata": {}},
                                     extra={"Cookie": stolen_admin})
    problem(status, headers, raw, 401, "AUTHENTICATION_REQUIRED")
    admin = login_admin()
    row("F13 Session revocation and suspension", "logout-all, admin suspension, admin logout",
        "Core refuses revoked access tokens; issued tickets expire within 300 s",
        "none", "renewal and admission 401 with a replayed access token; issued ticket still 200 within its "
                "lifetime; suspended viewer's renewal 401; revoked admin command 401")

    # F14. Expired upload session (synthetic: both owners' deadlines moved into the past together).
    movie = catalog("M34 expired upload")
    upload_id = str(uuid.UUID(intent(movie, str(uuid.uuid4()))["uploadId"]))
    created = (datetime.now(timezone.utc) - timedelta(hours=3)).replace(microsecond=0)
    expires = created + timedelta(hours=1)
    stack.sql("libra_media", f"UPDATE media_uploads SET created_at = '{created.isoformat()}', expires_at = "
                             f"'{expires.isoformat()}' WHERE id = '{upload_id}'")
    stack.sql("libra_core", f"UPDATE catalog_upload_intents SET created_at = '{created.isoformat()}', expires_at = "
                            f"'{expires.isoformat()}' WHERE id = '{upload_id}'")
    status, headers, raw = core_post(admin, f"/admin/uploads/{upload_id}/upload-url", None)
    require(status in (409, 410), "An expired upload was granted a signed URL")
    grant_code = f"{status} {problem(status, headers, raw, status, json.loads(raw).get('code'))['code']}"
    status, headers, raw = core_post(admin, f"/admin/uploads/{upload_id}/complete", None)
    complete_code = f"{status} {json.loads(raw).get('code')}"
    require(status in (409, 410), "An expired upload completed")
    wait_until(lambda: stack.sql("libra_media", f"SELECT state FROM media_uploads WHERE id = '{upload_id}'")
               == "EXPIRED", "Lifecycle did not expire the upload", 150)
    wait_until(lambda: candidate(movie["id"]).get("state") == "FAILED", "Core did not project the failure", 120)
    row("F14 Expired upload session", "deadline moved into the past in both databases (synthetic)",
        "no grant, no completion; lifecycle EXPIRED with a FAILED event", "none",
        f"grant {grant_code}; completion {complete_code}; upload EXPIRED; Core candidate FAILED")

    # F15. Source tampering: bytes that do not match the declared checksum never become READY.
    movie = catalog("M34 tampered source")
    upload_id = str(uuid.UUID(intent(movie, str(uuid.uuid4()))["uploadId"]))
    tampered = bytearray(clip)
    tampered[len(tampered) // 2] ^= 0xFF
    tampered_put = put(upload_id, bytes(tampered))
    status, headers, raw = core_post(admin, f"/admin/uploads/{upload_id}/complete", None)
    if tampered_put == 200:
        require(status == 202, "Completion of a stored source failed")

        def failed():
            current = upload_status(upload_id)
            require(current["assetState"] != "READY", "A tampered source became READY")
            return current if current["assetState"] == "FAILED" else None
        outcome = wait_until(failed, "Tampered source did not fail", 300)["failureCode"]
    else:
        outcome = f"PUT {tampered_put}; completion {status} {json.loads(raw).get('code')}"
        require(status in (409, 422), "Completion without a stored source was accepted")
        require(put(upload_id) == 200, "Correct bytes could not be uploaded after a refused PUT")
        admin.core("POST", f"/admin/uploads/{upload_id}/complete", status=202)
        media_ready(upload_id)
    row("F15 Source tampering", "one byte of the source flipped under the declared SHA-256",
        "never READY from the tampered bytes", "upload the declared bytes", f"{outcome}")

    # F16. Core's database unavailable while Media's READY event is consumed. A transient infrastructure failure must
    # not spend the event's bounded poison retries: once the database returns, Core projects READY by itself.
    movie, upload_id, job_id = submitted("M34 Core database outage at projection")
    wait_until(lambda: job(job_id)[0] == "TRANSCODING", "Job never reached TRANSCODING", 120)
    block = ("ALTER ROLE libra_core NOLOGIN; SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity "
             "WHERE usename = 'libra_core'")
    stack.sql("libra_media", block)
    try:
        wait_until(lambda: stack.sql("libra_media", "SELECT count(*) FROM media_outbox_events e JOIN media_uploads u ON "
                                                    "u.asset_id = e.asset_id JOIN media_jobs j ON j.upload_id = u.id "
                                                    "WHERE j.id = "
                                                    f"'{uuid.UUID(job_id)}' AND e.delivery_state = 'SENT' AND "
                                                    "e.payload->>'state' = 'READY'") == "1",
                   "Media did not publish READY during Core's database outage", 300)
        # Longer than three attempts that each wait out Core's 30 s connection timeout.
        time.sleep(150)
    finally:
        stack.sql("libra_media", "ALTER ROLE libra_core LOGIN")
    try:
        core_ready(movie["id"], 180)
        projected = "READY projected without an operator"
    except RuntimeError:
        projected = "candidate not READY"
    dead = stack.sql("libra_core", "SELECT count(*) FROM media_dead_letters")
    expect(projected.startswith("READY") and dead == "0",
           f"H1: after Core's database returned, {projected} with {dead} dead letter(s)")
    row("F16 Core database unavailable at projection", "libra_core role NOLOGIN and its sessions terminated for "
        "longer than three connection timeouts while READY is consumed", "event retried, not dead-lettered",
        "role LOGIN again", f"{projected}; {dead} dead letter(s)")

    network = f"{args.project}_default"

    def address(service):
        container = stack.docker("ps", "-a", "-q", service).split()[0]
        result = subprocess.run(["docker", "inspect", "-f",
                                 f'{{{{(index .NetworkSettings.Networks "{network}").IPAddress}}}}', container],
                                capture_output=True, text=True, timeout=30)
        require(result.returncode == 0, "Could not read a container address")
        return result.stdout.strip()

    # F17. Media restarted while storage is down: startup refuses (bucket check) and must come back on its own once
    # storage returns, as the infrastructure services do.
    media_before = address("media")
    stack.stop("s3-gateway")
    stack.docker("restart", "--timeout", "10", "media", timeout=120)
    time.sleep(40)
    state = stack.docker("ps", "-a", "--format", "{{.State}}", "media").strip()
    stack.docker("up", "-d", "--no-deps", "--no-recreate", "--wait", "--wait-timeout", "120", "s3-gateway", timeout=180)
    try:
        wait_until(lambda: stack.healthy("/api/media/actuator/health/readiness"), "Media did not return", 150)
        recovered = "Media ready again without an operator"
    except RuntimeError:
        recovered = "Media still down"
    media_moved = address("media") != media_before
    expect(recovered.startswith("Media ready"), f"H2: Media {state} during the outage; {recovered} after it")
    if not recovered.startswith("Media ready"):
        stack.start("media")
    session = admit(base_movie["id"])
    decode(session)
    row("F17 Media restarted during a storage outage", "docker stop s3-gateway; docker compose restart media",
        "startup refused while storage is down", "start s3-gateway only",
        f"Media {state} during the outage; address {'changed' if media_moved else 'unchanged'}; {recovered}; "
        "playback decodes")

    # F18. Core and Media restarted in the opposite order: Docker may give each the other's former address, which an
    # ingress that resolved its upstreams once at startup would keep using.
    before = {service: address(service) for service in ("core", "media")}
    stack.stop("core")
    stack.stop("media")
    stack.docker("up", "-d", "--no-deps", "--no-recreate", "media", timeout=240)
    stack.docker("up", "-d", "--no-deps", "--no-recreate", "core", timeout=240)
    after = {service: address(service) for service in ("core", "media")}
    moved = before != after
    try:
        stack.wait_services(120)
        reached = "both reachable through the ingress"
    except RuntimeError:
        reached = "unreachable through the ingress"
    expect(reached.startswith("both"), f"H4: Core and Media {reached} after their addresses "
                                       f"{'changed' if moved else 'stayed'}")
    if not reached.startswith("both"):
        stack.docker("restart", "nginx", timeout=120)
        stack.wait_services()
    decode(admit(base_movie["id"]))
    row("F18 Core and Media restarted in the opposite order", "docker stop core and media; start media, then core",
        "the ingress follows the services' current addresses", "none",
        f"addresses {'changed' if moved else 'unchanged'}; {reached}; playback decodes")

    # Convergence over everything above.
    duplicates = stack.sql("libra_media", "SELECT count(*) FROM (SELECT upload_id FROM media_jobs GROUP BY upload_id "
                                          "HAVING count(*) > 1) d")
    double_ready = stack.sql("libra_media", "SELECT count(*) FROM (SELECT payload->>'assetId' FROM media_outbox_events "
                                            "WHERE payload->>'state' = 'READY' GROUP BY 1 HAVING count(*) > 1) d")
    unsent = wait_until(lambda: stack.sql("libra_media", "SELECT count(*) FROM media_outbox_events WHERE "
                                                         "delivery_state <> 'SENT'") == "0",
                        "Outbox did not drain", 360)
    require(duplicates == "0" and double_ready == "0" and unsent, "The matrix did not converge")
    unpublished = catalog("M34 never published")
    status, headers, raw = core_post(viewer, "/playback/sessions",
                                     {"profileId": profile, "contentId": unpublished["id"]})
    require(status in (403, 404, 409), "Unpublished content was admitted")
    passed("Converged: no duplicate logical job, no second READY event, outbox drained, unpublished content refused")
    require(not FINDINGS, "Open findings: " + " | ".join(FINDINGS))

    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text(json.dumps({
        "result": "MEDIA_M34_FAULTS_PASS", "checkedAt": datetime.now(timezone.utc).isoformat(),
        "checks": m29.CHECKS, "rows": ROWS,
        "boundary": "Local disposable Compose: real compose.yaml and nginx.conf, packaged Core and Media JARs, "
                    "PostgreSQL, Kafka, Mailpit, SeaweedFS; docker stop/kill/pause as the injected faults; ffmpeg "
                    "as the player"
    }, indent=2) + "\n", encoding="utf-8")
    print("MEDIA_M34_FAULTS_PASS", flush=True)


def core_post(client, path, body, extra=None, csrf=None):
    """A Core command whose status the caller judges; returns (status, headers, raw). An extra Cookie header (a
    replayed access token) is sent together with the client's own cookies, the CSRF cookie among them."""
    if isinstance(client, Client):
        client.touch()
    headers = dict(extra or {})
    if csrf is None:
        _, _, raw = client.open("GET", "/api/core/v1/auth/csrf")
        csrf = json.loads(raw)
    headers[csrf["headerName"]] = csrf["token"]
    if "Cookie" in headers:
        headers["Cookie"] = "; ".join([headers["Cookie"], *(f"{c.name}={c.value}" for c in client.cookies)])
    if body is not None:
        headers["Content-Type"] = "application/json"
    return client.open("POST", "/api/core/v1" + path, body, headers)


def m29_register(args, premium):
    """A verified viewer, as M29 registers one; optionally with simulated Premium."""
    viewer = Client(args.base_url)
    email = "viewer-" + uuid.uuid4().hex + "@m34.example.test"
    password = secrets.token_urlsafe(32)
    viewer.core("POST", "/auth/register", status=202,
                body={"email": email, "displayName": "M34 viewer", "password": password})
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


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Library exceptions may include URLs, tickets or SQL output; only our fixed RuntimeError messages print.
        print(f"FAIL: {error if isinstance(error, RuntimeError) else type(error).__name__}", flush=True)
        raise SystemExit(1) from None
