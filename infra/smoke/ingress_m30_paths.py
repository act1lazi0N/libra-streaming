"""M30 path-abuse matrix for the repository ingress: which request targets beneath /api/media/ reach Media, and as
what path. Runs against nginx.conf in front of the M29 stand-in, which reports the URI it received in the
X-Upstream-Uri header. Requests are written byte for byte over a socket so no client normalises them. Never print
credentials or bodies."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import socket
import uuid

from core_http import require

SESSION = str(uuid.uuid4())
UPPER = SESSION.upper()
STREAM = f"/v1/streams/{SESSION}/"
CORRELATION = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
CHECKS = []

# Targets the ingress forwards, and the only URI Media may receive for each. A spelling Nginx itself decodes or
# collapses reaches Media as the canonical route, so the stream location's limits and refusals still apply to it.
FORWARDED = {
    "/api/media/actuator/health": "/actuator/health",
    "/api/media/actuator/health/liveness": "/actuator/health/liveness",
    "/api/media/actuator/health/readiness": "/actuator/health/readiness",
    f"/api/media{STREAM}master.m3u8": f"{STREAM}master.m3u8",
    f"/api/media{STREAM}rendition.m3u8": f"{STREAM}rendition.m3u8",
    f"/api/media{STREAM}segment-00001.ts": f"{STREAM}segment-00001.ts",
    f"/api/media{STREAM}%6Daster.m3u8": f"{STREAM}master.m3u8",
    f"/api/media{STREAM}master%2Em3u8": f"{STREAM}master.m3u8",
    f"/api/media/v1//streams/{SESSION}/master.m3u8": f"{STREAM}master.m3u8",
    f"/api/media{STREAM}./rendition.m3u8": f"{STREAM}rendition.m3u8",
    f"/api/media/v1/streams/x/../{SESSION}/segment-00001.ts": f"{STREAM}segment-00001.ts",
    f"/api/media/v1%2Fstreams/{SESSION}/master.m3u8": f"{STREAM}master.m3u8",
    f"http://localhost/api/media{STREAM}master.m3u8": f"{STREAM}master.m3u8",
}

# Targets that must never reach Media: Nginx answers them itself.
REFUSED = [
    f"/api/media/v1/streams;x=1/{SESSION}/master.m3u8",
    f"/api/media/v1/streams/{SESSION};x=1/master.m3u8",
    f"/api/media{STREAM}master.m3u8;x=1",
    f"/api/media{STREAM}master.m3u8;jsessionid=1",
    f"/api/media/v1/streams/{SESSION}%5Cmaster.m3u8",
    f"/api/media{STREAM}%255c..%255cmaster.m3u8",
    f"/api/media{STREAM}%2e%2e/master.m3u8",
    f"/api/media{STREAM}master.m3u8/",
    f"/api/media{STREAM}master.m3u8%20",
    f"/api/media/V1/streams/{SESSION}/master.m3u8",
    f"/api/media/v1/Streams/{SESSION}/master.m3u8",
    f"/api/media/v2/streams/{SESSION}/master.m3u8",
    f"/api/media/v1/streams/{UPPER}/master.m3u8",
    "/api/media/v1/streams",
    "/api/media/v1/streams/",
    "/api/media/",
    "/api/media",
    "/api/media/error",
    "/api/media/actuator",
    "/api/media/actuator/",
    "/api/media/actuator/info",
    "/api/media/actuator/env",
    "/api/media/actuator/health/",
    "/api/media/actuator/health/db",
    "/api/media/actuator/health;x=1",
    "/api/media/actuator/health/../env",
    "/api/media/internal/v1/uploads",
    "/api/media/%69nternal/v1/uploads",
    "/api/media/unprotected-probe",
]


def passed(message):
    CHECKS.append(message)
    print(f"PASS: {message}", flush=True)


def send(host, port, target, method="GET", body=False):
    """Writes one request exactly as given; returns (status, headers, body). A forwarded request is judged by its head
    alone; the stand-in answers it at full speed (X-Stub-Fast)."""
    with socket.create_connection((host, port), timeout=10) as connection:
        connection.sendall((f"{method} {target} HTTP/1.1\r\nHost: localhost\r\n"
                            "Cookie: __Secure-LIBRA_PLAYBACK=m30-path-canary\r\nX-Stub-Fast: 1\r\n"
                            # A body is announced and never sent: Nginx must refuse before reading it.
                            + ("Content-Length: 4\r\n" if body else "") + "Connection: close\r\n\r\n")
                           .encode("latin-1"))
        raw = b""
        while b"\r\n\r\n" not in raw:
            chunk = connection.recv(4096)
            if not chunk:
                break
            raw += chunk
        head, _, body = raw.partition(b"\r\n\r\n")
        lines = head.decode("latin-1").split("\r\n")
        status = int(lines[0].split(" ")[1])
        headers = {}
        for line in lines[1:]:
            name, _, value = line.partition(":")
            headers[name.strip().lower()] = value.strip()
        if "x-upstream-uri" in headers or method == "HEAD":
            return status, headers, b""
        length = int(headers.get("content-length", "-1"))
        while len(body) < length:
            chunk = connection.recv(4096)
            if not chunk:
                break
            body += chunk
        return status, headers, body


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--evidence", required=True)
    args = parser.parse_args()
    match = re.fullmatch(r"http://127\.0\.0\.1:([0-9]+)", args.base_url)
    require(match, "Ingress URL must be loopback")
    port = int(match[1])
    observed = {}

    for target, upstream in FORWARDED.items():
        status, headers, _ = send("127.0.0.1", port, target)
        observed[target.replace(SESSION, "{session}")] = headers.get("x-upstream-uri", status)
        require(status == 200 and headers.get("x-upstream-uri", "").replace(SESSION, "{session}")
                == upstream.replace(SESSION, "{session}"),
                f"Ingress did not forward {target.replace(SESSION, '{session}')} as the canonical route")
    passed(f"{len(FORWARDED)} targets reach Media only as Media's health or the canonical stream route")

    for target in REFUSED:
        status, headers, body = send("127.0.0.1", port, target)
        label = target.replace(SESSION, "{session}").replace(UPPER, "{SESSION}")
        observed[label] = headers.get("x-upstream-uri", status)
        require("x-upstream-uri" not in headers, f"{label} reached Media")
        require(status == 404 and headers.get("content-type", "").startswith("application/problem+json")
                and "no-store" in headers.get("cache-control", ""), f"{label} is not Nginx's 404 problem")
        problem = json.loads(body)
        require(problem.get("code") == "NOT_FOUND" and re.fullmatch(CORRELATION, problem.get("correlationId", ""))
                and "instance" not in problem, f"{label} problem shape changed")
    passed(f"{len(REFUSED)} other targets beneath /api/media/ answered 404 by Nginx without reaching Media")

    for method, body, status, code in (("POST", False, 403, "ACCESS_DENIED"), ("DELETE", False, 403, "ACCESS_DENIED"),
                                       ("HEAD", False, 403, None), ("GET", True, 400, "INVALID_REQUEST")):
        reply_status, headers, raw = send("127.0.0.1", port, "/api/media/actuator/health/readiness", method, body)
        observed[f"{method}{' with body' if body else ''} /api/media/actuator/health/readiness"] =             headers.get("x-upstream-uri", reply_status)
        require("x-upstream-uri" not in headers and reply_status == status, f"{method} on Media health reached Media")
        if code:
            require(json.loads(raw).get("code") == code, "Health refusal is not the problem")
    passed("Media's health takes only a GET without a body: other methods 403, an announced body 400, from Nginx")

    Path(args.evidence).write_text(json.dumps({
        "result": "INGRESS_M30_PATHS_PASS", "checkedAt": datetime.now(timezone.utc).isoformat(), "checks": CHECKS,
        "observed": observed,
        "boundary": "Repository nginx.conf in Nginx 1.29.3 in front of a stand-in for Media; no Media, ticket or "
                    "storage involved"}, indent=2) + "\n", encoding="utf-8")
    print("INGRESS_M30_PATHS_PASS", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"FAIL: {error if isinstance(error, RuntimeError) else type(error).__name__}", flush=True)
        raise SystemExit(1) from None
