"""RA03 seed: catalog content, real HLS assets and viewers for the browser acceptance, all through the ingress.
Never prints credentials, tickets or bodies; the seed file it writes holds generated passwords and is deleted by the
runner. Needs ffmpeg and the runner's MEDIA_S3_BROWSER_ENDPOINT / LIBRA_RA03_SEED_ADMIN_* variables."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time
import urllib.request
import uuid

from core_http import require, wait_until
from media_m29_http import Viewer

LOOPBACK = r"http://127\.0\.0\.1:[0-9]+"
UPLOAD_SECONDS = 420


def passed(message):
    print(f"PASS: {message}", flush=True)


def metadata(title, description, tier):
    return {"title": title, "description": description, "genres": ["drama"], "releaseYear": 2026, "language": "vi",
            "credits": [], "imageReferences": [], "accessTier": tier}


def make_clip(path, seconds, frequency):
    """640x360 H.264 high + AAC: the M29 recipe at the size and length the browser acceptance needs."""
    encode = subprocess.run([os.environ.get("LIBRA_FFMPEG_PATH") or "ffmpeg", "-v", "error", "-nostdin", "-f", "lavfi",
                             "-i", "testsrc2=size=640x360:rate=30", "-f", "lavfi",
                             "-i", f"sine=frequency={frequency}:sample_rate=48000", "-t", str(seconds),
                             "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-c:a", "aac",
                             "-b:a", "48k", "-shortest", "-movflags", "+faststart", str(path)],
                            capture_output=True, timeout=180)
    require(encode.returncode == 0 and path.is_file(), "Could not generate a source clip")


def login_admin(base):
    admin = Viewer(base)
    admin.core("POST", "/auth/login", body={"email": os.environ["LIBRA_RA03_SEED_ADMIN_EMAIL"],
                                            "password": os.environ["LIBRA_RA03_SEED_ADMIN_PASSWORD"]})
    return admin


def retry_unavailable(call, attempts=4):
    """Core answers 503 MEDIA_UNAVAILABLE when its call to Media fails; the committed intent stays, so a repeat is safe.
    The first upload of a run hit this once on a freshly started Media (not reproduced on the next run)."""
    for attempt in range(attempts):
        try:
            return call()
        except RuntimeError as error:
            if not str(error).endswith("got 503") or attempt == attempts - 1:
                raise
            time.sleep(2)


def create_upload(admin, content_id, body):
    """POST /uploads is idempotent on requestId: a repeat after a 503 returns the committed intent (200 or 201)."""
    for attempt in range(4):
        _, _, token = admin.open("GET", "/api/core/v1/auth/csrf")
        csrf = json.loads(token)
        code, headers, raw = admin.open("POST", f"/api/core/v1/admin/catalog/{content_id}/uploads", body,
                                        {csrf["headerName"]: csrf["token"], "Content-Type": "application/json"})
        if code in (200, 201):
            return json.loads(raw)
        require(code == 503 and attempt < 3, f"POST upload intent: expected 201, got {code}")
        time.sleep(2)


def upload_to_ready(base, browser_endpoint, content_id, clip_path):
    """Upload intent, signed PUT, completion, then wait for Media READY and Core's candidate projection."""
    admin = login_admin(base)
    started = time.monotonic()
    current, _ = admin.core("GET", f"/admin/catalog/{content_id}")
    clip = clip_path.read_bytes()
    created = create_upload(admin, content_id, {
        "requestId": str(uuid.uuid4()), "expectedVersion": current["version"], "byteLength": len(clip),
        "sha256": hashlib.sha256(clip).hexdigest()})
    upload_id = str(uuid.UUID(created["uploadId"]))
    grant, _ = retry_unavailable(lambda: admin.core("POST", f"/admin/uploads/{upload_id}/upload-url"))
    require(grant["method"] == "PUT" and grant["url"].startswith(browser_endpoint + "/libra-source/staging/"),
            "Grant did not target the browser staging endpoint")
    put = urllib.request.Request(grant["url"], data=clip, headers=grant["requiredHeaders"], method="PUT")
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(put, timeout=60) as response:
        require(response.status == 200, "Signed PUT to SeaweedFS failed")
    queued, _ = retry_unavailable(lambda: admin.core("POST", f"/admin/uploads/{upload_id}/complete", status=202))
    require(queued["assetState"] == "QUEUED", "Completion did not queue processing")
    queued_at = time.monotonic()

    def media_ready():
        status, _ = admin.core("GET", f"/admin/uploads/{upload_id}")
        require(status.get("failureCode") is None, "Processing failed: " + str(status.get("failureCode")))
        return status if status["assetState"] == "READY" else None

    wait_until(media_ready, "Media worker did not make the upload READY", UPLOAD_SECONDS)
    media_at = time.monotonic()

    def core_ready():
        state, _ = admin.core("GET", f"/admin/catalog/{content_id}")
        return state if (state.get("candidate") or {}).get("state") == "READY" else None

    wait_until(core_ready, "Core did not project Media's READY event from Kafka", 120)
    done = time.monotonic()
    return {"putAndQueueSeconds": round(queued_at - started, 1), "mediaReadySeconds": round(media_at - queued_at, 1),
            "coreProjectionSeconds": round(done - media_at, 1)}


def register_viewer(base, mail_url, display_name, verify, premium):
    viewer = Viewer(base)
    email = "ra03-" + uuid.uuid4().hex + "@ra03.example.test"
    password = secrets.token_urlsafe(32)
    viewer.core("POST", "/auth/register", status=202, body={"email": email, "displayName": display_name,
                                                            "password": password})
    if verify:
        mail = Viewer(mail_url)

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
    me, _ = viewer.core("GET", "/me")
    require(me["emailVerified"] is verify, "Viewer verification state differs from the plan")
    if premium:
        viewer.core("POST", "/subscriptions/simulate", body={"plan": "PREMIUM_30_DAYS"},
                    headers={"Idempotency-Key": str(uuid.uuid4())})
        subscription, _ = viewer.core("GET", "/subscriptions")
        require(subscription["status"] == "ACTIVE", "Premium viewer is not ACTIVE")
    profiles, _ = viewer.core("GET", "/profiles")
    if premium:
        viewer.core("POST", "/profiles", status=201, body={"name": "Thứ hai"})
        profiles, _ = viewer.core("GET", "/profiles")
        require(len(profiles) == 2, "Premium viewer must have two profiles")
    return {"email": email, "password": password, "displayName": display_name, "verified": verify,
            "premium": premium, "profiles": [{"id": p["id"], "name": p["name"]} for p in profiles]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--mail-url", required=True)
    parser.add_argument("--out", required=True, type=Path)
    args = parser.parse_args()
    for url in (args.base_url, args.mail_url, os.environ.get("MEDIA_S3_BROWSER_ENDPOINT", "")):
        require(re.fullmatch(LOOPBACK, url), "Smoke URLs and the storage endpoint must be loopback")
    require(os.environ.get("LIBRA_RA03_SEED_ADMIN_EMAIL") and os.environ.get("LIBRA_RA03_SEED_ADMIN_PASSWORD"),
            "Administrator credentials are not set")
    browser_endpoint = os.environ["MEDIA_S3_BROWSER_ENDPOINT"]
    base = args.base_url
    clips = args.out.parent / "clips"
    clips.mkdir(parents=True, exist_ok=True)

    admin = login_admin(base)
    public = Viewer(base)

    def create(kind, title, tier, parent=None, ordinal=None):
        body = {"kind": kind, "metadata": metadata(title, "Disposable RA03 browser acceptance fixture", tier)}
        if parent:
            body["parentId"] = parent
            body["ordinal"] = ordinal
        created, _ = admin.core("POST", "/admin/catalog", status=201, body=body)
        return created["id"]

    # Plan: key -> (kind, title, tier, seconds, parent key, ordinal, tone). Only MOVIE and EPISODE carry media.
    plan = {
        "free_movie": ("MOVIE", "RA03 Phim lẻ miễn phí", "FREE", 40, None, None, 440),
        "premium_movie": ("MOVIE", "RA03 Phim lẻ Premium", "PREMIUM", 12, None, None, 520),
        "series": ("SERIES", "RA03 Phim bộ", "FREE", None, None, None, None),
        "season": ("SEASON", "RA03 Phim bộ - Mùa 1", "FREE", None, "series", 1, None),
        "episode_1": ("EPISODE", "RA03 Phim bộ - Tập 1", "FREE", 12, "season", 1, 600),
        "episode_2": ("EPISODE", "RA03 Phim bộ - Tập 2", "FREE", 12, "season", 2, 680),
        "hidden_movie": ("MOVIE", "RA03 Phim lẻ đã ẩn", "FREE", 12, None, None, 760),
    }
    ids = {}
    for key, (kind, title, tier, _, parent, ordinal, _) in plan.items():
        ids[key] = create(kind, title, tier, ids[parent] if parent else None, ordinal)
    passed("Catalog drafts created: free and Premium movies, one series with a season and two episodes, one to hide")

    with ThreadPoolExecutor(max_workers=8) as pool:
        # Viewers do not depend on content: they register while Media works.
        viewer_jobs = {
            # Core limits logins to 10 per email per 15 minutes: the suite spreads its Premium logins over three accounts.
            "premium_viewer": pool.submit(register_viewer, base, args.mail_url, "RA03 Premium", True, True),
            "premium_viewer_2": pool.submit(register_viewer, base, args.mail_url, "RA03 Premium 2", True, True),
            "premium_viewer_3": pool.submit(register_viewer, base, args.mail_url, "RA03 Premium 3", True, True),
            "free_viewer": pool.submit(register_viewer, base, args.mail_url, "RA03 Free", True, False),
            "unverified_viewer": pool.submit(register_viewer, base, args.mail_url, "RA03 Unverified", False, False),
        }
        upload_jobs = {}
        for key, (_, _, _, seconds, _, _, tone) in plan.items():
            if seconds is None:
                continue
            clip_path = clips / f"{key}.mp4"
            make_clip(clip_path, seconds, tone)
            upload_jobs[key] = pool.submit(upload_to_ready, base, browser_endpoint, ids[key], clip_path)
        timings = {key: job.result() for key, job in upload_jobs.items()}
        viewers = {key: job.result() for key, job in viewer_jobs.items()}
    for key, timing in timings.items():
        passed(f"{key}: upload to READY in Media and Core ({timing['putAndQueueSeconds']} s PUT, "
               f"{timing['mediaReadySeconds']} s worker, {timing['coreProjectionSeconds']} s projection)")
    passed("Three Premium viewers (two profiles each), a free and an unverified viewer registered and in the planned states")

    def publish(key):
        current, _ = admin.core("GET", f"/admin/catalog/{ids[key]}")
        admin.core("POST", f"/admin/catalog/{ids[key]}/publication", body={"expectedVersion": current["version"]})

    # Containers first; a descendant is only visible once every ancestor is published, whatever the order.
    for key in ("series", "season", "episode_1", "episode_2", "free_movie", "premium_movie", "hidden_movie"):
        publish(key)
    current, _ = admin.core("GET", f"/admin/catalog/{ids['hidden_movie']}")
    admin.core("DELETE", f"/admin/catalog/{ids['hidden_movie']}/publication?expectedVersion={current['version']}")
    for key in ("free_movie", "premium_movie", "episode_1", "episode_2"):
        catalog, _ = public.core("GET", f"/catalog/{ids[key]}")
        require(catalog["mediaReady"] is True, f"{key} is not playable in the public catalog")
    public.core("GET", f"/catalog/{ids['hidden_movie']}", status=404)
    seasons, _ = public.core("GET", f"/catalog/{ids['series']}/children?limit=100")
    require([c["id"] for c in seasons] == [ids["season"]], "Series does not list its season")
    episodes, _ = public.core("GET", f"/catalog/{ids['season']}/children?limit=100")
    require([c["id"] for c in episodes] == [ids["episode_1"], ids["episode_2"]], "Season does not list its episodes in order")
    passed("Published content is public and playable; the hidden movie answers 404; the series lists season and episodes")

    contents = {key: {"id": ids[key], "title": plan[key][1], "kind": plan[key][0], "tier": plan[key][2],
                      "durationSeconds": plan[key][3], "parentKey": plan[key][4], "ordinal": plan[key][5]}
                for key in plan}
    seed = {"admin": {"email": os.environ["LIBRA_RA03_SEED_ADMIN_EMAIL"],
                      "password": os.environ["LIBRA_RA03_SEED_ADMIN_PASSWORD"]},
            "contents": contents, "viewers": viewers, "timings": timings}
    args.out.write_text(json.dumps(seed, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    try:
        os.chmod(args.out, 0o600)
    except OSError:
        pass
    passed("Seed file written (content withheld)")
    for clip_path in clips.glob("*.mp4"):
        clip_path.unlink()


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Library exceptions may include URLs, tokens or SQL output; only our fixed RuntimeError messages print.
        print(f"FAIL: {error if isinstance(error, RuntimeError) else type(error).__name__}", flush=True)
        raise SystemExit(1) from None
