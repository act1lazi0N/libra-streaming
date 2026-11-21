"""RA03: wait until Core, Media, Analytics and the web app answer through the ingress. Never prints bodies."""
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
        return 0, "", b""
    with response:
        return response.status, response.headers.get("Content-Type", ""), response.read(1024 * 1024)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True)
    base = parser.parse_args().base_url
    require(re.fullmatch(r"http://127\.0\.0\.1:[0-9]+", base), "Smoke URL must be loopback")

    def healthy(path):
        status, _, raw = fetch(base + path)
        # Nginx answers 502 while an upstream is still starting.
        if status != 200:
            return False
        try:
            return json.loads(raw).get("status") == "UP"
        except ValueError:
            return False

    wait_until(lambda: healthy("/api/core/actuator/health"), "Core did not become healthy through Nginx", 180)
    wait_until(lambda: healthy("/api/media/actuator/health/readiness"), "Media did not become ready through Nginx", 180)
    # Analytics exposes /actuator/health publicly (permitAll); Nginx strips the /api/recommendations prefix.
    wait_until(lambda: healthy("/api/recommendations/actuator/health"),
               "Analytics did not become healthy through Nginx", 180)

    def web_home():
        status, content_type, _ = fetch(base + "/")
        return status == 200 and content_type.lower().startswith("text/html")

    wait_until(web_home, "The web application does not serve HTML through Nginx", 120)
    print("PASS: Core, Media, Analytics and the web application answer through the repository ingress", flush=True)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"FAIL: {error if isinstance(error, RuntimeError) else type(error).__name__}", flush=True)
        raise SystemExit(1) from None
