"""M29 ingress stream-location check against a slow stand-in for Media (compose.ingress-m29.yaml)."""
import argparse
import concurrent.futures
import json
from pathlib import Path
import re
import socket
import time
import urllib.error
import urllib.request
import uuid

from core_http import require

UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
CRLF = "\r\n"
CHECKS = []


def passed(message):
    CHECKS.append(message)
    print(f"PASS: {message}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--evidence")
    parser.add_argument("--upstream-down", action="store_true",
                        help="Media is stopped: only check that the ingress answers with a problem")
    args = parser.parse_args()
    require(re.fullmatch(r"http://127\.0\.0\.1:[0-9]+", args.base_url), "Ingress URL must be loopback")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def get(path, headers=None, method="GET"):
        started = time.monotonic()
        request = urllib.request.Request(args.base_url + path, headers=headers or {}, method=method)
        try:
            response = opener.open(request, timeout=60)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.headers, response.read(65536), time.monotonic() - started

    def stream(session, name="master.m3u8", headers=None, method="GET"):
        return get(f"/api/media/v1/streams/{session}/{name}", headers, method)

    def problem(status, headers, body, expected_status, code, retry):
        """An answer written by the ingress itself: a no-store problem that never came from the stand-in."""
        require(status == expected_status and headers.get("Content-Type") == "application/problem+json"
                and headers.get("Cache-Control") == "no-store" and headers.get("Retry-After") == retry
                and not body.startswith(b"uri="), f"Expected an ingress {expected_status} {code} problem")
        value = json.loads(body)
        require(value["code"] == code and value["status"] == expected_status
                and re.fullmatch(UUID, value["correlationId"]), f"Ingress {expected_status} problem shape changed")

    if args.upstream_down:
        problem(*stream(str(uuid.uuid4()))[:3], 503, "MEDIA_UNAVAILABLE", None)
        print("PASS: With Media down the stream route is a no-store 503 problem without Retry-After", flush=True)
        return

    def squat(session, method, header):
        """A request whose announced body never arrives."""
        host, port = args.base_url.removeprefix("http://").split(":")
        connection = socket.create_connection((host, int(port)), timeout=3)
        head = f"{method} /api/media/v1/streams/{session}/master.m3u8 HTTP/1.1{CRLF}Host: m29{CRLF}{header}{CRLF}{CRLF}"
        connection.sendall(head.encode())
        return connection

    session = str(uuid.uuid4())
    status, headers, body, _ = stream(session, headers={"Cookie": "__Secure-LIBRA_PLAYBACK=t"})
    require(status == 200 and body.startswith(f"uri=/v1/streams/{session}/master.m3u8 ".encode())
            and b"cookie=__Secure-LIBRA_PLAYBACK=t " in body and "Set-Cookie" not in headers,
            "The stream location did not forward the stripped path and the cookie unchanged")
    passed("Stream location forwards /v1/streams/{sessionId}/{name} and the ticket cookie unchanged")

    status, headers, body, _ = stream(session, "segment-00009.ts")
    require(status == 503 and headers.get("Retry-After") == "1" and json.loads(body).get("from") == "media",
            "Media's own 503 was replaced by the ingress")
    passed("Media's 503 with Retry-After passes through uninterrupted")

    for path in (f"/api/media/v1/streams/{session}/source.mp4", f"/api/media/v1/streams/{session}/%2e%2e/master.m3u8",
                 "/api/media/v1/streams/not-a-session/master.m3u8", f"/api/media/v1/streams/{session}/x/master.m3u8",
                 f"/api/media/v1/streams/{session}/index.html", f"/api/media/v1/streams/{session}.jpg"):
        problem(*get(path)[:3], 404, "NOT_FOUND", None)
    passed("Malformed stream paths (including .html and .jpg names) are a 404 problem from the ingress and never "
           "reach Media")

    squatted = str(uuid.uuid4())
    squatters = [squat(squatted, "POST", "Content-Length: 10000000") for _ in range(4)]
    squatters += [squat(squatted, "GET", "Content-Length: 10000000"), squat(squatted, "GET", "Transfer-Encoding: chunked")]
    try:
        for connection in squatters:
            try:
                status_line = connection.recv(64).split(CRLF.encode())[0]
            except socket.timeout:
                status_line = b"no answer within 3 s"
            require(status_line in (b"HTTP/1.1 403 Forbidden", b"HTTP/1.1 400 Bad Request"),
                    "A request with an unsent body was not refused at once")
        require(stream(squatted)[0] == 200, "Requests with unsent bodies held the session's streams")
    finally:
        for connection in squatters:
            connection.close()
    for method in ("POST", "PUT", "DELETE"):
        problem(*stream(squatted, method=method)[:3], 403, "ACCESS_DENIED", None)
    status, headers, _, _ = stream(squatted, method="HEAD")
    require(status == 403 and headers.get("Cache-Control") == "no-store", "HEAD reached Media")
    problem(*stream(squatted, headers={"Content-Length": "1"}, method="GET")[:3], 400, "INVALID_REQUEST", None)
    passed("Methods other than GET and requests announcing a body are refused before the stream limits, so an "
           "unsent body cannot hold a session's streams")

    with concurrent.futures.ThreadPoolExecutor(32) as pool:
        busy = str(uuid.uuid4())
        results = list(pool.map(lambda _: stream(busy), range(5)))
        refused = [r for r in results if r[0] == 503]
        require(len(refused) == 1 and sum(r[0] == 200 for r in results) == 4, "Per-session ingress limit is not 4")
        problem(*refused[0][:3], 503, "MEDIA_UNAVAILABLE", "1")
        require(refused[0][3] < 2, "Ingress refusal was not immediate")
        require(stream(busy)[0] == 200, "The session's share was not returned after its streams ended")
        passed("A fifth concurrent stream of one session is refused at once with 503 and Retry-After: 1; "
               "the share returns afterwards")
        sessions = [str(uuid.uuid4()) for _ in range(5)]
        results = list(pool.map(lambda i: stream(sessions[i % 5]), range(17)))
        require(sum(r[0] == 200 for r in results) == 16 and sum(r[0] == 503 for r in results) == 1,
                "Per-client ingress limit is not 16")
        problem(*next(r for r in results if r[0] == 503)[:3], 503, "MEDIA_UNAVAILABLE", "1")
        passed("A seventeenth concurrent stream from one client address is refused with the same 503 problem")
    require(args.evidence, "Evidence path required")
    Path(args.evidence).parent.mkdir(parents=True, exist_ok=True)
    with open(args.evidence, "w", encoding="utf-8") as evidence:
        json.dump({"result": "INGRESS_M29_PASS", "checks": CHECKS}, evidence, indent=2)
        evidence.write("\n")
    print("INGRESS_M29_PASS", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"FAIL: {error if isinstance(error, RuntimeError) else type(error).__name__}", flush=True)
        raise SystemExit(1) from None
