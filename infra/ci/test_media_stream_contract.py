"""M27-M29 protected stream contract, cross-checked against Core's playback contract and the ingress configuration;
no HTTP runtime is involved."""
import json
import re
import unittest
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]
STREAMS = yaml.safe_load((ROOT / "contracts/http/media-streams.v1.yaml").read_text(encoding="utf-8"))
PLAYBACK = yaml.safe_load((ROOT / "contracts/http/core-playback.v1.yaml").read_text(encoding="utf-8"))
SESSION = "66666666-6666-4666-8666-666666666666"


class MediaStreamContractTest(unittest.TestCase):
    def operation(self):
        return STREAMS["paths"]["/v1/streams/{sessionId}/{name}"]["get"]

    def test_core_manifest_path_reaches_the_master_route_after_nginx_strips_the_prefix(self):
        manifest = re.compile(PLAYBACK["components"]["schemas"]["Session"]["properties"]["manifestPath"]["pattern"])
        external = f"/api/media/v1/streams/{SESSION}/master.m3u8"
        self.assertRegex(external, manifest)
        internal = external.removeprefix("/api/media")
        session, name = re.fullmatch(r"/v1/streams/([^/]+)/([^/]+)", internal).groups()
        self.assertEqual(SESSION, session)
        names = re.compile(next(p for p in self.operation()["parameters"] if p["name"] == "name")["schema"]["pattern"])
        self.assertRegex(name, names)

    def test_name_vocabulary_is_closed(self):
        names = re.compile(next(p for p in self.operation()["parameters"] if p["name"] == "name")["schema"]["pattern"])
        for name in ("master.m3u8", "rendition.m3u8", "segment-00000.ts", "segment-00299.ts"):
            self.assertRegex(name, names)
        for name in ("source.mp4", "../master.m3u8", "master.m3u8/", "segment-0000.ts", "segment-00000.TS",
                     "rendition.m3u8.bak", "%2e%2e"):
            self.assertNotRegex(name, names)

    def test_ticket_is_a_cookie_only_credential_with_core_scoping(self):
        scheme = STREAMS["components"]["securitySchemes"]["playbackTicket"]
        self.assertEqual(("apiKey", "cookie", "__Secure-LIBRA_PLAYBACK"), (scheme["type"], scheme["in"], scheme["name"]))
        self.assertEqual([{"playbackTicket": []}], STREAMS["security"])
        issued = yaml.safe_dump(PLAYBACK)
        self.assertIn("__Secure-LIBRA_PLAYBACK", issued)
        self.assertIn("Path=/api/media/v1/streams/{sessionId}/", issued)
        description = STREAMS["info"]["description"]
        for claim in ("libra-core-playback", "libra-media", "media-playback", "300 seconds", "Authorization header"):
            self.assertIn(claim, description)

    def test_responses_are_uncacheable_and_failures_sanitized(self):
        responses = self.operation()["responses"]
        self.assertEqual("no-store", responses["200"]["headers"]["Cache-Control"]["schema"]["const"])
        self.assertEqual({"application/vnd.apple.mpegurl", "video/mp2t"}, set(responses["200"]["content"]))
        self.assertEqual({"200", "401", "403", "404", "503"}, set(responses))
        self.assertEqual({"401": ["AUTHENTICATION_REQUIRED"], "403": ["ACCESS_DENIED"], "404": ["NOT_FOUND"],
                          "503": ["MEDIA_UNAVAILABLE"]}, self.operation()["x-error-codes"])
        self.assertNotIn("post", STREAMS["paths"]["/v1/streams/{sessionId}/{name}"])

    def test_stream_limits_are_retryable_and_use_foundation_codes(self):
        responses = self.operation()["responses"]
        # A stream limit is a retryable 5xx, never a 4xx an HLS player would give up on.
        self.assertNotIn("429", responses)
        self.assertEqual("#/components/responses/RetryLater", responses["503"]["$ref"])
        retry = STREAMS["components"]["responses"]["RetryLater"]
        self.assertEqual(1, retry["headers"]["Retry-After"]["schema"]["const"])
        foundation = yaml.safe_load((ROOT / "contracts/http/core-foundation.v1.yaml").read_text(encoding="utf-8"))
        codes = foundation["components"]["schemas"]["Problem"]["properties"]["code"]["enum"]
        for listed in self.operation()["x-error-codes"].values():
            self.assertTrue(set(listed) <= set(codes), listed)
        self.assertIn("JSON integers", STREAMS["info"]["description"])

    def test_ingress_forwards_exactly_the_contract_route_and_answers_its_own_refusals_as_problems(self):
        nginx = (ROOT / "infra/nginx/nginx.conf").read_text(encoding="utf-8")
        location = re.search(r'location ~ "(\^/api/media/v1/streams/[^"]+)" \{(.*?)\n        \}', nginx, re.S)
        self.assertIsNotNone(location, "The ingress has no exact stream location")
        route, body = re.compile(location.group(1)), location.group(2)
        names = re.compile(next(p for p in self.operation()["parameters"] if p["name"] == "name")["schema"]["pattern"])
        for name in ("master.m3u8", "rendition.m3u8", "segment-00000.ts", "segment-00299.ts", "source.mp4",
                     "segment-0000.ts", "master.M3U8", "rendition.m3u8.bak", "x/master.m3u8"):
            # The ingress forwards a name exactly when the contract's vocabulary admits it.
            self.assertEqual(bool(names.fullmatch(name)), bool(route.fullmatch(f"/api/media/v1/streams/{SESSION}/{name}")), name)
        for session in ("ABCDEF66-6666-4666-8666-666666666666", "not-a-session", SESSION + "0"):
            self.assertIsNone(route.fullmatch(f"/api/media/v1/streams/{session}/master.m3u8"), session)
        self.assertIn("rewrite ^/api/media(/.*)$ $1 break;", body)
        # Protected bytes are never cached or spooled to the ingress's disk.
        self.assertIn("proxy_max_temp_file_size 0;", body)
        self.assertNotIn("proxy_cache", nginx)
        self.assertNotIn("proxy_intercept_errors", nginx)
        self.assertEqual({"stream_session": "4", "stream_client": "16"}, dict(re.findall(r"limit_conn (\w+) (\d+);", body)))
        foundation = yaml.safe_load((ROOT / "contracts/http/core-foundation.v1.yaml").read_text(encoding="utf-8"))
        problem = foundation["components"]["schemas"]["Problem"]
        bodies = re.findall(r"return (\d{3}) '(\{.*?\})';", nginx)
        self.assertEqual({("400", "INVALID_REQUEST"), ("403", "ACCESS_DENIED"), ("404", "NOT_FOUND"),
                          ("503", "MEDIA_UNAVAILABLE")},
                         {(status, json.loads(template.replace("$problem_id", SESSION))["code"])
                          for status, template in bodies})
        for status, template in bodies:
            value = json.loads(template.replace("$problem_id", SESSION))
            self.assertTrue(set(problem["required"]) <= set(value), status)
            self.assertEqual(int(status), value["status"])
            self.assertIn(value["code"], problem["properties"]["code"]["enum"])
        busy = re.search(r"location @stream_busy \{(.*?)\n        \}", nginx, re.S).group(1)
        self.assertIn("add_header Retry-After 1 always;", busy)
        unavailable = re.search(r"location @stream_unavailable \{(.*?)\n        \}", nginx, re.S).group(1)
        self.assertNotIn("Retry-After", unavailable)
        self.assertIn("error_page 502 504 = @stream_unavailable;", body)
        # Methods and bodies are refused by `if`/`return`, which run in the rewrite phase, before limit_conn (preaccess).
        self.assertIn("if ($request_method != GET) {", body)
        self.assertIn("if ($stream_body) {", body)
        # Every answer Nginx writes on these paths is a problem, whatever the name's extension.
        self.assertEqual(4, nginx.count("types { }"))
        self.assertIn("Nginx", STREAMS["info"]["description"])


if __name__ == "__main__":
    unittest.main()
