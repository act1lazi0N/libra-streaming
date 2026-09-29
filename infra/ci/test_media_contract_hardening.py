"""Validate M02 failure fixtures against the versioned API/event schemas."""
import unittest
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[2]


def read(path):
    return yaml.safe_load((ROOT / path).read_text(encoding="utf-8"))


CORE = read("contracts/http/core-media-uploads.v1.yaml")
MEDIA = read("contracts/http/media-internal-uploads.v1.yaml")
FOUNDATION = read("contracts/http/core-foundation.v1.yaml")
EVENT = read("contracts/events/media-asset-state.v1.schema.json")
CATALOG = read("contracts/http/core-catalog-profiles.v1.json")
PLAYBACK = read("contracts/http/core-playback.v1.yaml")
CASES = read("infra/ci/fixtures/media-upload-m02.v1.yaml")["cases"]

CONTENT = "11111111-1111-4111-8111-111111111111"
REQUEST = "22222222-2222-4222-8222-222222222222"
UPLOAD = "33333333-3333-4333-8333-333333333333"
BINDING = "44444444-4444-4444-8444-444444444444"
ASSET = "55555555-5555-4555-8555-555555555555"
JOB = "77777777-7777-4777-8777-777777777777"


def valid(schema, body):
    return not list(Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(body))


def operations(document):
    return {operation["operationId"]: operation for methods in document["paths"].values()
            for method, operation in methods.items() if method in ("get", "post", "put")}


def submitted_status(contract):
    status = {"uploadId": UPLOAD, "contentId": CONTENT, "bindingId": BINDING,
              "assetId": ASSET, "assetVersion": 1, "uploadState": "SUBMITTED",
              "assetState": "QUEUED", "jobId": JOB, "attemptCount": 0,
              "failureCode": None, "expiresAt": "2026-09-24T09:00:00Z"}
    if contract == "core":
        status["catalogVersionAtReservation"] = 5
    return status


class MediaHardeningContractsTest(unittest.TestCase):
    def test_failure_matrix_has_valid_operation_and_response_examples(self):
        self.assertEqual(len(CASES), len({case["id"] for case in CASES}))
        self.assertEqual(23, len(CASES))
        documents = {"core": CORE, "media": MEDIA}
        for case in CASES:
            with self.subTest(case=case["id"]):
                self.assertIn(case["protectedEffect"], {
                    "none", "original_intent_only", "core_intent_and_binding_only_remote_uncertain",
                    "expiry_failure_transition_only", "remote_job_uncertain", "original_job_only",
                    "original_session_only"})
                document = documents[case["contract"]]
                operation = operations(document)[case["operationId"]]
                for outcome in case["outcomes"]:
                    status = outcome["status"]
                    if status >= 400:
                        self.assertIn(outcome["code"], operation["x-error-codes"][str(status)])
                        self.assertIn("default", operation["responses"])
                        problem = {"type": "about:blank", "title": "Request failed",
                                   "status": status, "detail": "Request failed.",
                                   "code": outcome["code"], "correlationId": REQUEST}
                        self.assertTrue(valid(FOUNDATION["components"]["schemas"]["Problem"], problem))
                    else:
                        response = operation["responses"][str(status)]
                        self.assertEqual("submitted_status", outcome["body"])
                        ref = response["content"]["application/json"]["schema"]["$ref"]
                        schema = document["components"]["schemas"][ref.split("/")[-1]]
                        self.assertTrue(valid(schema, submitted_status(case["contract"])))

    def test_credentials_scopes_and_csrf_are_separate(self):
        core = operations(CORE)
        media = operations(MEDIA)
        allowed_codes = set(FOUNDATION["components"]["schemas"]["Problem"]["properties"]["code"]["enum"])
        for operation in (*core.values(), *media.values()):
            self.assertEqual("#/components/responses/Problem", operation["responses"]["default"]["$ref"])
            for status, codes in operation["x-error-codes"].items():
                self.assertGreaterEqual(int(status), 400)
                self.assertLessEqual(int(status), 599)
                self.assertTrue(set(codes).issubset(allowed_codes))
        self.assertEqual({"cookieAuth": []}, CORE["security"][0])
        self.assertEqual({"coreServiceBearer": []}, MEDIA["security"][0])
        for name, operation in core.items():
            self.assertEqual("ADMIN", operation["x-required-role"], name)
            self.assertTrue(operation["x-requires-current-session"], name)
            if name != "createMediaUpload":
                self.assertTrue(operation["x-requires-upload-creator"], name)
        self.assertEqual("core.media.uploads:read", media["readUpload"]["x-required-scope"])
        for name in ("ensureUpload", "issueUploadUrl", "completeUpload"):
            self.assertEqual("core.media.uploads:write", media[name]["x-required-scope"])
        for path, methods in CORE["paths"].items():
            if "post" in methods:
                refs = {value["$ref"] for value in methods["post"]["parameters"] if "$ref" in value}
                self.assertIn("#/components/parameters/Csrf", refs, path)
        for name in ("issueMediaUploadUrl", "issueUploadUrl"):
            operation = core[name] if name in core else media[name]
            self.assertEqual("no-store", operation["responses"]["200"]["headers"]["Cache-Control"]["schema"]["const"])
        unauthorized = {**submitted_status("core"), "url": "https://example.invalid/private"}
        self.assertFalse(valid(CORE["components"]["schemas"]["UploadStatus"], unauthorized))

    def test_exact_event_selection_and_publication_remain_separate(self):
        event = {"eventId": REQUEST, "eventType": "MediaAssetStateChanged", "schemaVersion": 1,
                 "aggregateId": ASSET, "aggregateVersion": 2,
                 "occurredAt": "2026-09-24T08:30:00Z", "correlationId": REQUEST,
                 "payload": {"contentId": CONTENT, "bindingId": BINDING, "assetId": ASSET,
                             "assetVersion": 1, "state": "READY", "durationSeconds": 60}}
        self.assertTrue(valid(EVENT, event))
        self.assertEqual(ASSET, event["aggregateId"])
        self.assertEqual((CONTENT, BINDING, ASSET, 1),
                         tuple(event["payload"][field] for field in
                               ("contentId", "bindingId", "assetId", "assetVersion")))
        self.assertFalse(valid(EVENT, {**event, "payload": {**event["payload"], "state": "QUEUED"}}))
        self.assertFalse(valid(EVENT, {**event, "payload": {**event["payload"], "durationSeconds": None}}))
        # Cross-field equality is a consumer invariant; a JSON Schema alone cannot prove it.
        wrong = {**event, "payload": {**event["payload"], "bindingId": UPLOAD}}
        self.assertTrue(valid(EVENT, wrong))
        self.assertNotEqual(BINDING, wrong["payload"]["bindingId"])
        self.assertIn("/v1/admin/catalog/{id}/publication", CATALOG["paths"])
        self.assertIn("/v1/playback/sessions", PLAYBACK["paths"])
        self.assertFalse(any("publication" in path or "playback" in path for path in CORE["paths"]))


if __name__ == "__main__":
    unittest.main()
