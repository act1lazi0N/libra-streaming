"""M33: wait until Core, Media and the harness answer through the ingress. Never prints bodies."""
import argparse
import json
import re
import urllib.error
import urllib.request

from core_http import require, wait_until


def fetch(url):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        response = opener.open(urllib.request.Request(url), timeout=10)
    except urllib.error.HTTPError as error:
        response = error
    except OSError:
        return 0, b""
    with response:
        return response.status, response.read(1024 * 1024)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    base = parser.parse_args().base_url
    require(re.fullmatch(r"http://127\.0\.0\.1:[0-9]+", base), "Smoke URL must be loopback")

    def healthy(path):
        status, raw = fetch(base + path)
        # Nginx answers 502 while an upstream is still starting.
        return status == 200 and json.loads(raw).get("status") == "UP"

    wait_until(lambda: healthy("/api/core/actuator/health"), "Core did not become healthy through Nginx", 180)
    wait_until(lambda: healthy("/api/media/actuator/health/readiness"), "Media did not become ready through Nginx", 180)
    wait_until(lambda: fetch(base + "/m33/")[0] == 200, "The browser harness is not served through Nginx", 60)
    print("PASS: Core, Media and the browser harness answer through the repository ingress", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"FAIL: {error if isinstance(error, RuntimeError) else type(error).__name__}", flush=True)
        raise SystemExit(1) from None
