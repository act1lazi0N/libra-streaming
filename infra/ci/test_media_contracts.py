"""Executable M01 examples; this does not test HTTP or Media runtime behavior."""
import base64
import unittest
from pathlib import Path

import yaml
from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[2]
CORE = yaml.safe_load((ROOT / "contracts/http/core-media-uploads.v1.yaml").read_text(encoding="utf-8"))
MEDIA = yaml.safe_load((ROOT / "contracts/http/media-internal-uploads.v1.yaml").read_text(encoding="utf-8"))
FOUNDATION = yaml.safe_load((ROOT / "contracts/http/core-foundation.v1.yaml").read_text(encoding="utf-8"))
EVENT = yaml.safe_load((ROOT / "contracts/events/media-asset-state.v1.schema.json").read_text(encoding="utf-8"))

CONTENT = "11111111-1111-4111-8111-111111111111"
REQUEST = "22222222-2222-4222-8222-222222222222"
UPLOAD = "33333333-3333-4333-8333-333333333333"
BINDING = "44444444-4444-4444-8444-444444444444"
ASSET = "55555555-5555-4555-8555-555555555555"
JOB = "77777777-7777-4777-8777-777777777777"
EXPIRES = "2026-09-24T09:00:00Z"


def errors(schema, value):
    return list(Draft202012Validator(schema, format_checker=FormatChecker()).iter_errors(value))


class MediaContractExamplesTest(unittest.TestCase):
    def test_create_ensure_and_status_examples(self):
        create = {"requestId": REQUEST, "expectedVersion": 4, "byteLength": 1024, "sha256": "a" * 64}
        ensure = {"requestId": REQUEST, "contentId": CONTENT, "bindingId": BINDING,
                  "assetId": ASSET, "assetVersion": 1, "byteLength": 1024,
                  "sha256": "a" * 64, "expiresAt": EXPIRES}
        media_status = {"uploadId": UPLOAD, "contentId": CONTENT, "bindingId": BINDING,
                        "assetId": ASSET, "assetVersion": 1, "uploadState": "OPEN",
                        "assetState": "UPLOADING", "jobId": None, "attemptCount": 0,
                        "failureCode": None, "expiresAt": EXPIRES}
        core_status = {**media_status, "catalogVersionAtReservation": 5}
        self.assertEqual([], errors(CORE["components"]["schemas"]["CreateUpload"], create))
        self.assertEqual([], errors(MEDIA["components"]["schemas"]["EnsureUpload"], ensure))
        self.assertEqual([], errors(MEDIA["components"]["schemas"]["MediaStatus"], media_status))
        self.assertEqual([], errors(CORE["components"]["schemas"]["UploadStatus"], core_status))
        queued = {**media_status, "uploadState": "SUBMITTED", "assetState": "QUEUED", "jobId": JOB}
        self.assertEqual([], errors(MEDIA["components"]["schemas"]["MediaStatus"], queued))
        self.assertEqual([], errors(CORE["components"]["schemas"]["UploadStatus"], {**queued, "catalogVersionAtReservation": 5}))
        expired = {**media_status, "uploadState": "EXPIRED", "assetState": "FAILED",
                   "failureCode": "UPLOAD_EXPIRED"}
        self.assertEqual([], errors(MEDIA["components"]["schemas"]["MediaStatus"], expired))
        self.assertTrue(errors(MEDIA["components"]["schemas"]["MediaStatus"],
                               {**queued, "jobId": None}))

    def test_url_and_problem_examples(self):
        url = {"uploadId": UPLOAD, "method": "PUT",
               "url": "http://localhost:8333/libra-source/staging/example?X-Amz-Signature=example",
               "expiresAt": "2026-09-24T08:15:00Z",
               "requiredHeaders": {"Content-Type": "video/mp4",
                                   "x-amz-checksum-sha256": base64.b64encode(bytes.fromhex("a" * 64)).decode("ascii")}}
        problem = {"type": "about:blank", "title": "Gone", "status": 410,
                   "detail": "Gone.", "code": "UPLOAD_EXPIRED",
                   "correlationId": "66666666-6666-4666-8666-666666666666"}
        for contract in (CORE, MEDIA):
            self.assertEqual([], errors(contract["components"]["schemas"]["UploadUrl"], url))
        self.assertEqual([], errors(FOUNDATION["components"]["schemas"]["Problem"], problem))
        self.assertTrue(errors(CORE["components"]["schemas"]["UploadUrl"],
                               {**url, "method": "GET"}))

    def test_invalid_input_and_existing_event_compatibility(self):
        create = {"requestId": REQUEST, "expectedVersion": 4, "byteLength": 268435457, "sha256": "A" * 64}
        self.assertTrue(errors(CORE["components"]["schemas"]["CreateUpload"], create))
        event = {"eventId": REQUEST, "eventType": "MediaAssetStateChanged", "schemaVersion": 1,
                 "aggregateId": ASSET, "aggregateVersion": 2,
                 "occurredAt": "2026-09-24T08:30:00Z", "correlationId": UPLOAD,
                 "payload": {"contentId": CONTENT, "bindingId": BINDING, "assetId": ASSET,
                             "assetVersion": 1, "state": "READY", "durationSeconds": 60}}
        self.assertEqual([], errors(EVENT, event))
        self.assertTrue(errors(EVENT, {**event, "payload": {**event["payload"], "state": "QUEUED"}}))
        self.assertTrue(errors(EVENT, {**event, "payload": {**event["payload"], "durationSeconds": None}}))

    def test_operation_security_and_status_alignment(self):
        core_paths = CORE["paths"]
        media_paths = MEDIA["paths"]
        self.assertEqual({"cookieAuth": []}, CORE["security"][0])
        self.assertEqual({"coreServiceBearer": []}, MEDIA["security"][0])
        for path, methods in core_paths.items():
            for method, operation in methods.items():
                if method == "post":
                    self.assertIn("Csrf", [p["$ref"].split("/")[-1] for p in operation["parameters"]], path)
        self.assertIn("202", core_paths["/v1/admin/uploads/{uploadId}/complete"]["post"]["responses"])
        self.assertIn("202", media_paths["/internal/v1/uploads/{uploadId}/complete"]["post"]["responses"])
        core_status = CORE["components"]["schemas"]["UploadStatus"]
        media_status = MEDIA["components"]["schemas"]["MediaStatus"]
        self.assertEqual(set(media_status["required"]), set(core_status["required"]) - {"catalogVersionAtReservation"})
        for field in media_status["properties"]:
            self.assertEqual(media_status["properties"][field], core_status["properties"][field])
        self.assertEqual(media_status["allOf"], core_status["allOf"])


if __name__ == "__main__":
    unittest.main()
