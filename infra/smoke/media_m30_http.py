"""M30 abuse, cache and expiry checks of protected playback through the repository ingress, after the whole M29 slice.
Never print credentials, tickets or bodies."""
from datetime import datetime, timezone
import hashlib
import json
import os
import re
import socket
import struct
import threading
import time

from core_http import require
import media_m29_http as m29
from media_m29_http import ROOT, TICKET_COOKIE, passed, problem

CHECKS = m29.CHECKS


def raw(base_url, target, headers=(), method="GET"):
    """One request written byte for byte; returns (status, headers, body). Reads the body by its Content-Length."""
    host, port = re.fullmatch(r"http://(127\.0\.0\.1):([0-9]+)", base_url).groups()
    with socket.create_connection((host, int(port)), timeout=30) as connection:
        head = f"{method} {target} HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
        connection.sendall((head + "".join(h + "\r\n" for h in headers) + "\r\n").encode("latin-1"))
        data = b""
        while True:
            chunk = connection.recv(65536)
            if not chunk:
                break
            data += chunk
    head, _, body = data.partition(b"\r\n\r\n")
    lines = head.decode("latin-1").split("\r\n")
    fields = {}
    for line in lines[1:]:
        name, _, value = line.partition(":")
        require(name.strip().lower() not in fields, "A response repeated a header")
        fields[name.strip().lower()] = value.strip()
    if "content-length" in fields and method != "HEAD":
        require(int(fields["content-length"]) == len(body), "A response body does not match its Content-Length")
    return int(lines[0].split(" ")[1]), fields, body


class Fields(dict):
    """Lower-case header map with the get() problem() expects."""

    def get(self, name, default=None):
        return super().get(name.lower(), default)


def refused(reply, status, code):
    return problem(reply[0], Fields(reply[1]), reply[2], status, code)


def ticket_expiry(viewer, session_id):
    expiries = [c.expires for c in viewer.cookies if c.name == TICKET_COOKIE
                and c.path == f"/api/media/v1/streams/{session_id}/"]
    require(len(expiries) == 1 and expiries[0], "Ticket cookie has no expiry")
    return expiries[0]


def main():
    args = m29.arguments(__doc__)
    evidence = ROOT / "target/verification/media-m30.json"
    evidence.unlink(missing_ok=True)
    played = m29.play(args)
    viewer, session_id, segments = played["viewer"], played["session_id"], played["segments"]
    base, stream_path, session = args.base_url, played["stream_path"], session_id
    # The ticket Core renewed at the end of the M29 slice; its exp is the cookie's Max-Age (five minutes).
    ticket = viewer.ticket(session_id)
    expires = ticket_expiry(viewer, session_id)
    require(240 <= expires - time.time() <= 301, "Core's ticket lifetime changed")
    cookie = f"Cookie: {TICKET_COOKIE}={ticket}"
    largest = max(segments, key=lambda name: len(raw(base, stream_path + name, [cookie])[2]))
    stored = {name: raw(base, stream_path + name, [cookie])[2] for name in ("master.m3u8", "rendition.m3u8", largest)}
    require(stored["master.m3u8"] == played["master"] and stored["rendition.m3u8"] == played["rendition"],
            "Playlists changed between requests")

    # 1. Spellings: what Nginx normalises reaches Media as the canonical route (with its limits); nothing else does.
    same = {f"/api/media/v1/streams/{session}/%6Daster.m3u8": "master.m3u8",
            f"/api/media/v1//streams/{session}/master.m3u8": "master.m3u8",
            f"/api/media/v1/streams/{session}/./rendition.m3u8": "rendition.m3u8",
            f"/api/media/v1/streams/x/../{session}/{largest}": largest,
            f"/api/media/v1%2Fstreams/{session}/master.m3u8": "master.m3u8",
            f"http://localhost/api/media/v1/streams/{session}/master.m3u8": "master.m3u8"}
    for target, name in same.items():
        status, _, body = raw(base, target, [cookie])
        require(status == 200 and body == stored[name], "A normalised spelling did not serve the canonical object")
    others = [f"/api/media/v1/streams;x=1/{session}/master.m3u8", f"/api/media/v1/streams/{session};x=1/master.m3u8",
              f"{stream_path}master.m3u8;jsessionid=1", f"/api/media/v1/streams/{session}%5Cmaster.m3u8",
              f"{stream_path}%255c..%255cmaster.m3u8", f"{stream_path}master.m3u8/", f"{stream_path}master.m3u8%20",
              f"/api/media/V1/streams/{session}/master.m3u8", f"/api/media/v1/Streams/{session}/master.m3u8",
              f"/api/media/v2/streams/{session}/master.m3u8", f"/api/media/v1/streams/{session.upper()}/master.m3u8",
              "/api/media/actuator/info", "/api/media/actuator/env", "/api/media/actuator/health/db",
              "/api/media/actuator", "/api/media/error", "/api/media/", "/api/media",
              "/api/media/internal/v1/uploads"]
    for target in others:
        body = refused(raw(base, target, [cookie]), 404, "NOT_FOUND")
        require("instance" not in body, "A spelling outside the exact route reached Media")
    require(raw(base, "/api/media/actuator/health/liveness")[0] == 200, "Media liveness is not reachable")
    passed(f"{len(same)} spellings Nginx normalises served the canonical object; {len(others)} other targets "
           "beneath /api/media/ were Nginx's 404 problem with the valid ticket attached (Media never saw them)")

    # 2. Caches and validators: nothing lets an authorized response be reused, revalidated or served in part.
    target = stream_path + largest
    status, fields, body = raw(base, target, [cookie])
    require(status == 200 and fields.get("cache-control") == "no-store", "Segment is cacheable through the ingress")
    for absent in ("etag", "last-modified", "accept-ranges", "content-range", "age", "expires", "set-cookie", "vary"):
        require(absent not in fields, f"A segment response carries {absent}")
    for conditional in ("If-None-Match: *", 'If-None-Match: "x"', "If-Modified-Since: Mon, 01 Jan 2090 00:00:00 GMT",
                        "Range: bytes=0-0", "Range: bytes=100-", 'If-Range: "x"', "Cache-Control: only-if-cached"):
        status, fields, body = raw(base, target, [cookie, conditional])
        require(status == 200 and body == stored[largest] and "content-range" not in fields,
                "A validator or range changed an authorized response")
        refused(raw(base, target, [conditional]), 401, "AUTHENTICATION_REQUIRED")
    refused(raw(base, target), 401, "AUTHENTICATION_REQUIRED")
    passed("Responses carry no-store and no validator, age or range; conditional and range requests get the whole "
           "object with the ticket and 401 without it; an authorized response never answers the next anonymous one")

    # 3. Repeated requests and HEAD.
    digests = {hashlib.sha256(raw(base, target, [cookie])[2]).hexdigest() for _ in range(20)}
    require(digests == {hashlib.sha256(stored[largest]).hexdigest()}, "Repeated GETs differed")
    status, _, body = raw(base, target, [cookie], method="HEAD")
    require(status == 403 and not body, "HEAD reached Media or carried a body")
    passed("Twenty repeated GETs of a segment returned identical bytes; HEAD is refused at the ingress")

    # 4. Players that hang up mid-body give their streams back, at Nginx and at Media.
    host, port = re.fullmatch(r"http://(127\.0\.0\.1):([0-9]+)", base).groups()
    for _ in range(8):
        player = socket.create_connection((host, int(port)), timeout=30)
        player.sendall((f"GET {target} HTTP/1.1\r\nHost: localhost\r\n{cookie}\r\nConnection: close\r\n\r\n")
                       .encode("latin-1"))
        require(player.recv(12).startswith(b"HTTP/1.1 200"), "A stream was not admitted")
        # Linger 0: the close is a reset, as when a player is killed.
        player.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("hh" if os.name == "nt" else "ii", 1, 0))
        player.close()
    deadline = time.monotonic() + 10
    while True:
        results = []
        threads = [threading.Thread(target=lambda: results.append(raw(base, target, [cookie])[0])) for _ in range(4)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        if results == [200] * 4:
            break
        require(time.monotonic() < deadline, "Abandoned streams did not give the session's share back")
        time.sleep(0.5)
    passed("Eight players that hung up mid-segment left the session its four concurrent streams")

    # 5. Core's ticket expires in the middle of the presentation: every protected request stops. The playback session
    # expires with it, so (as Core's playback contract states) renewal is refused and a new admission continues.
    time.sleep(max(0.0, expires - time.time()))
    # The cookie's expiry is whole seconds on the host clock, Core's exp on the container's: wait for Media's own
    # verdict, bounded, rather than trusting a fixed margin.
    deadline = time.time() + 5
    while raw(base, stream_path + "master.m3u8", [cookie])[0] == 200:
        require(time.time() < deadline, "Media accepted the ticket more than 5 s after its expiry")
        time.sleep(0.2)
    for name in ("master.m3u8", "rendition.m3u8", segments[0], largest):
        refused(raw(base, stream_path + name, [cookie]), 401, "AUTHENTICATION_REQUIRED")
    refused(raw(base, stream_path + "%6Daster.m3u8", [cookie]), 401, "AUTHENTICATION_REQUIRED")
    late, _ = viewer.core("POST", f"/playback/sessions/{session_id}/renew", status=409)
    require(late["code"] == "PLAYBACK_EXPIRED", "Renewal after expiry was not PLAYBACK_EXPIRED")
    readmitted, _ = viewer.core("POST", "/playback/sessions", status=201,
                                body={"profileId": played["profile"], "contentId": played["content_id"]})
    fresh_path = f"/api/media/v1/streams/{readmitted['sessionId']}/"
    fresh = viewer.ticket(readmitted["sessionId"])
    require(fresh != ticket and readmitted["sessionId"] != session_id, "Readmission reused the expired session")
    for name in ("master.m3u8", "rendition.m3u8", *segments):
        status, _, body = raw(base, fresh_path + name, [f"Cookie: {TICKET_COOKIE}={fresh}"])
        require(status == 200 and body == (m29.WORK / "hls" / name).read_bytes(),
                "A new admission does not continue the presentation with the same bytes")
    refused(raw(base, fresh_path + largest, [cookie]), 401, "AUTHENTICATION_REQUIRED")
    refused(raw(base, stream_path + largest, [f"Cookie: {TICKET_COOKIE}={fresh}"]), 403, "ACCESS_DENIED")
    passed("After Core's ticket expired mid-presentation the master, rendition and segments were 401 through Nginx "
           "and renewal was PLAYBACK_EXPIRED; a new admission continued every object, the expired ticket opened "
           "neither session and the new ticket only its own")

    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text(json.dumps({
        "result": "MEDIA_M30_SMOKE_PASS", "checkedAt": datetime.now(timezone.utc).isoformat(), "checks": CHECKS,
        "segments": len(segments), "largestSegmentBytes": len(stored[largest]),
        "boundary": "Local disposable Compose: real compose.yaml and nginx.conf, packaged Core and Media JARs, "
                    "PostgreSQL, Kafka, Mailpit, SeaweedFS; raw sockets and ffmpeg as players; no browser"
    }, indent=2) + "\n", encoding="utf-8")
    print("MEDIA_M30_SMOKE_PASS", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"FAIL: {error if isinstance(error, RuntimeError) else type(error).__name__}", flush=True)
        raise SystemExit(1) from None
