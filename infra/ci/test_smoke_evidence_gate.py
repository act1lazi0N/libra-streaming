import base64
import ast
from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import tempfile
import time
import unittest
import uuid
from unittest import mock

from check_smoke_evidence import ACCEPTANCE_EVIDENCE, EVIDENCE, collect, main, secret_findings


class SmokeEvidenceGateTest(unittest.TestCase):
    def setUp(self):
        self.run_id = "fixture-run-35"
        self.enterContext(mock.patch.dict(os.environ, {"LIBRA_VERIFY_RUN_ID": self.run_id}))
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.output = self.root / "target/verification"
        self.output.mkdir(parents=True)
        self.summary = self.output / "smoke-gate.json"
        self.jar_time = time.time_ns() - 10_000_000_000
        self.started_at = str(self.jar_time - 1_000_000_000)
        os.environ["LIBRA_VERIFY_STARTED_AT"] = self.started_at
        self.revision = {"commit": "a1" * 20, "dirty": False, "changedPaths": 0, "stateSha256": "b2" * 32}
        self.enterContext(mock.patch("check_smoke_evidence.revision_record", return_value=self.revision))
        self.jars = [self.root / f"services/{module}/target/{module}-service-0.1.0-SNAPSHOT.jar"
                     for module in ("core", "media")]
        for jar in self.jars:
            jar.parent.mkdir(parents=True)
            jar.write_bytes(b"fake packaged JAR fixture")
            os.utime(jar, ns=(self.jar_time, self.jar_time))
        self.documents = {}
        self.write_evidence("toolchain.json", {"java": "openjdk version fixture", "revision": self.revision})
        self.tests = {"result": "TESTS_PASS", "runId": self.run_id, "startedAt": self.started_at}
        self.write_evidence("tests.json", self.tests)
        for name, result in EVIDENCE.items():
            document = {
                "runId": self.run_id,
                "checks": ["Scheduled encrypted mail queue to Mailpit and single-use email verification",
                           "Protected HLS segments delivered through the repository ingress"],
                "boundary": "Local disposable Compose: packaged Core and Media JARs, PostgreSQL and Kafka",
            }
            if result is not None:
                document["result"] = result
            if name == "media-m29.json":
                document.update(checkedAt="2026-10-25T12:34:56.123456+00:00", segments=3, deliveredBytes=12345)
            self.documents[name] = document
            self.write_evidence(name, document)

    def write_evidence(self, name, document):
        path = self.output / name
        path.write_text(json.dumps(document, indent=2), encoding="utf-8")
        fresh = self.jar_time + 5_000_000_000
        os.utime(path, ns=(fresh, fresh))

    def assert_rejected(self, message, acceptance=False):
        self.summary.write_text('{"result": "SMOKE_EVIDENCE_PASS"}', encoding="utf-8")
        with self.assertRaisesRegex(ValueError, message):
            collect(self.root, acceptance=acceptance)
        self.assertFalse(self.summary.exists())

    def assert_secret_rejected(self, rule, text):
        for name, document in self.documents.items():
            with self.subTest(file=name, rule=rule):
                self.write_evidence(name, {**document, "detail": text})
                self.summary.write_text('{}', encoding="utf-8")
                with self.assertRaises(ValueError) as error:
                    collect(self.root)
                self.assertEqual(str(error.exception), f"{rule}: {name}")
                self.assertFalse((self.output / name).exists())
                self.assertFalse(self.summary.exists())
                self.write_evidence(name, document)

    def test_missing_evidence_file_fails(self):
        for name, document in self.documents.items():
            with self.subTest(file=name):
                (self.output / name).unlink()
                self.assert_rejected("Missing smoke evidence: " + name)
                self.write_evidence(name, document)

    def test_missing_toolchain_fails(self):
        (self.output / "toolchain.json").unlink()
        self.assert_rejected("Missing toolchain evidence: toolchain.json")

    def test_stale_or_equal_toolchain_fails_against_each_jar(self):
        for jar in self.jars:
            for difference in (-1_000_000_000, 0):
                with self.subTest(jar=jar.name, difference=difference):
                    newer_jar = self.jar_time + 2_000_000_000
                    os.utime(jar, ns=(newer_jar, newer_jar))
                    stale = newer_jar + difference
                    os.utime(self.output / "toolchain.json", ns=(stale, stale))
                    self.assert_rejected("Stale toolchain evidence: toolchain.json")
                    os.utime(jar, ns=(self.jar_time, self.jar_time))

    def test_run_identity_mismatch_or_missing(self):
        for name, document in self.documents.items():
            for run_id in ("another-run", None):
                with self.subTest(file=name, run_id=run_id):
                    changed = {**document, "runId": run_id}
                    if run_id is None:
                        del changed["runId"]
                    self.write_evidence(name, changed)
                    self.assert_rejected("Run identity mismatch: " + name)
            self.write_evidence(name, document)

    def test_unset_run_identity_fails(self):
        del os.environ["LIBRA_VERIFY_RUN_ID"]
        self.assert_rejected("Missing run identity: LIBRA_VERIFY_RUN_ID")

    def test_empty_run_identity_fails(self):
        os.environ["LIBRA_VERIFY_RUN_ID"] = ""
        self.assert_rejected("Missing run identity: LIBRA_VERIFY_RUN_ID")

    def test_deletes_all_offending_json_before_other_checks(self):
        names = ["tests.json", "contracts.json", "toolchain.json", *EVIDENCE]
        for name in names:
            self.write_evidence(name, {"detail": "-----" + "BEGIN FAKE TEST KEY-----"})
        self.jars[0].unlink()
        del os.environ["LIBRA_VERIFY_RUN_ID"]
        self.assert_rejected("PEM: contracts.json")
        for name in names:
            self.assertFalse((self.output / name).exists(), name)

    def test_missing_jar_fails(self):
        for jar in self.jars:
            with self.subTest(jar=jar.name):
                jar.unlink()
                self.assert_rejected("Missing packaged JAR")
                jar.write_bytes(b"fake packaged JAR fixture")
                os.utime(jar, ns=(self.jar_time, self.jar_time))

    def test_stale_or_equal_evidence_fails_against_each_jar(self):
        for name, document in self.documents.items():
            for jar in self.jars:
                for difference in (-1_000_000_000, 0):
                    with self.subTest(file=name, jar=jar.name, difference=difference):
                        newer_jar = self.jar_time + 2_000_000_000
                        os.utime(jar, ns=(newer_jar, newer_jar))
                        stale = newer_jar + difference
                        os.utime(self.output / name, ns=(stale, stale))
                        self.assert_rejected("Stale smoke evidence: " + name)
                        os.utime(jar, ns=(self.jar_time, self.jar_time))
                        self.write_evidence(name, document)

    def test_rejects_pem(self):
        self.assert_secret_rejected("PEM", "-----" + "BEGIN FAKE TEST KEY-----")

    def test_rejects_bearer_token(self):
        self.assert_secret_rejected("Bearer token", "bEaReR fake-token-" + str(uuid.uuid4()))

    def test_rejects_bearer_token_with_escaped_newline(self):
        self.assert_secret_rejected("Bearer token", "Bearer fake-token-fixture\nnext line")

    def test_rejects_bearer_token_with_comma(self):
        self.assert_secret_rejected("Bearer token", "Bearer fake-token-fixture, next value")

    def test_rejects_bearer_token_with_semicolon(self):
        self.assert_secret_rejected("Bearer token", "Bearer fake-token-fixture; next value")

    def test_rejects_presigned_url(self):
        self.assert_secret_rejected(
            "Presigned URL", "https://example.test/fixture?X-Amz-Signature=fake-fixture")

    def test_unlink_failure_does_not_stop_sanitizing_remaining_files(self):
        first = self.output / "contracts.json"
        second = self.output / "tests.json"
        for path in (first, second):
            self.write_evidence(path.name, {"detail": "Bearer fake-token-fixture"})
        original_unlink = Path.unlink

        def unlink(path, *args, **kwargs):
            if path == first:
                raise PermissionError("inert unlink failure")
            return original_unlink(path, *args, **kwargs)

        with mock.patch.object(Path, "unlink", unlink):
            self.assert_rejected("Unable to remove evidence: contracts.json")
        self.assertTrue(first.exists())
        self.assertFalse(second.exists())

    def test_read_failure_does_not_stop_sanitizing_remaining_files(self):
        first = self.output / "contracts.json"
        second = self.output / "tests.json"
        self.write_evidence(first.name, {"detail": "inert unreadable fixture"})
        self.write_evidence(second.name, {"detail": "Bearer fake-token-fixture"})
        original_read_bytes = Path.read_bytes

        def read_bytes(path):
            if path == first:
                raise PermissionError("inert read failure")
            return original_read_bytes(path)

        with mock.patch.object(Path, "read_bytes", read_bytes):
            self.assert_rejected("Unable to read evidence: contracts.json")
        self.assertFalse(second.exists())

    def test_rejects_jwt_shaped_text(self):
        fake = ".".join("eyJ" + part for part in ("FakeTestHeader", "FakeTestPayload"))
        self.assert_secret_rejected("JWT", fake + ".FakeTestSignature")

    def test_rejects_cookie_headers(self):
        for header in ("Set-Cookie", "Cookie", "sEt-CoOkIe", "cOoKiE"):
            self.assert_secret_rejected("Cookie header", header + ": fake-session=obviously-fake")

    def test_rejects_long_base64(self):
        fake = base64.b64encode(b"obviously fake test fixture with no credential value" * 2).decode("ascii")
        self.assert_secret_rejected("long base64", fake)

    def test_rejects_base64_key_shape_with_slash_and_padding(self):
        # Deterministic inert 32-byte fixture; no generated or usable credential.
        fake = base64.b64encode(b"inert fixture only " + bytes(range(243, 256))).decode("ascii")
        self.assertIn("/", fake)
        self.assertTrue(fake.endswith("="))
        self.assert_secret_rejected("long base64", fake)

    def test_rejects_32_byte_base64_key_shape(self):
        fake = base64.b64encode(b"inert fixture only " + bytes(range(13))).decode("ascii")
        self.assertEqual(len(fake), 44)
        self.assertNotIn("/", fake)
        self.assert_secret_rejected("long base64", fake)

    def test_accepts_paths_and_short_hex(self):
        for text in ("services/core/src/main/resources/db/migration", "a1" * 15, "A1" * 15):
            with self.subTest(text=text):
                self.write_evidence("contracts.json", {"detail": text})
                self.assertEqual(collect(self.root)["result"], "SMOKE_EVIDENCE_PASS")

    def test_wrong_empty_or_missing_result_fails(self):
        for name, expected in EVIDENCE.items():
            if expected is None:
                continue
            for result in ("WRONG", "", None):
                with self.subTest(file=name, result=result):
                    self.write_evidence(name, {**self.documents[name], "result": result})
                    self.assert_rejected("Invalid smoke result: " + name)
            document = dict(self.documents[name])
            del document["result"]
            self.write_evidence(name, document)
            self.assert_rejected("Invalid smoke result: " + name)
            self.write_evidence(name, self.documents[name])

    def test_empty_or_invalid_checks_fail(self):
        for name, document in self.documents.items():
            for checks in ([], None, "check", {}, [""], ["  "], [1], ["passed", False]):
                with self.subTest(file=name, checks=checks):
                    self.write_evidence(name, {**document, "checks": checks})
                    self.assert_rejected("Invalid or empty smoke checks: " + name)
            missing = dict(document)
            del missing["checks"]
            self.write_evidence(name, missing)
            self.assert_rejected("Invalid or empty smoke checks: " + name)
            self.write_evidence(name, document)

    def test_invalid_json_or_non_object_fails(self):
        for name, document in self.documents.items():
            for raw in ("{invalid", "[]", "null", '"text"', "1"):
                with self.subTest(file=name, raw=raw):
                    (self.output / name).write_text(raw, encoding="utf-8")
                    self.assert_rejected("Invalid JSON|must be a JSON object")
            self.write_evidence(name, document)

    def test_happy_path_accepts_realistic_text_and_writes_summary(self):
        document = {**self.documents["media-m29.json"], "assetId": str(uuid.uuid4()),
                    "checks": self.documents["media-m29.json"]["checks"] + [
                        "__Secure-libra-playback ticket admitted; HLS master and rendition returned no-store",
                        "PostgreSQL/Kafka/storage checks passed with ffprobe and FFmpeg",
                        "No cookie, bearer header, wrong cookie name, identity cookies alone and another session's "
                        "ticket refused; non-Premium admission refused without a ticket",
                    ]}
        self.write_evidence("media-m29.json", document)
        summary = collect(self.root)
        self.assertEqual(summary["result"], "SMOKE_EVIDENCE_PASS")
        self.assertEqual(summary["files"], {
            name: {"checkCount": 5 if name == "media-m29.json" else 2, "newerThanJars": True}
            for name in EVIDENCE
        })
        self.assertEqual(json.loads(self.summary.read_text(encoding="utf-8")), summary)

    def test_missing_or_malformed_run_start_fails(self):
        for value in (None, "", "abc", "-1", "1.0", " 1", "1\n"):
            with self.subTest(value=value):
                if value is None:
                    os.environ.pop("LIBRA_VERIFY_STARTED_AT", None)
                else:
                    os.environ["LIBRA_VERIFY_STARTED_AT"] = value
                self.assert_rejected("Missing or malformed run start: LIBRA_VERIFY_STARTED_AT")

    def test_jars_must_be_built_after_run_start(self):
        started = int(self.started_at)
        for jar in self.jars:
            for difference in (-1_000_000_000, 0):
                with self.subTest(jar=jar.name, difference=difference):
                    os.utime(jar, ns=(started + difference, started + difference))
                    self.assert_rejected("JAR not built in this run: " + jar.name)
            os.utime(jar, ns=(self.jar_time, self.jar_time))

    def test_requires_test_evidence(self):
        (self.output / "tests.json").unlink()
        self.assert_rejected("Missing test evidence: tests.json")

    def test_requires_matching_successful_test_evidence(self):
        for field in ("result", "runId", "startedAt"):
            for value in (None, "", "wrong", 1):
                with self.subTest(field=field, value=value):
                    document = {**self.tests, field: value}
                    if value is None:
                        del document[field]
                    self.write_evidence("tests.json", document)
                    self.assert_rejected("tests.json")

    def test_invalid_metadata_json_fails(self):
        for name in ("toolchain.json", "tests.json"):
            original = (self.output / name).read_bytes()
            for raw in (b"{invalid", b"[]", b"null", b'"text"', b"\xff"):
                with self.subTest(file=name, raw=raw):
                    (self.output / name).write_bytes(raw)
                    self.assert_rejected(name)
            (self.output / name).write_bytes(original)

    def test_malformed_revision_fails(self):
        invalid = [None, [], {}, {**self.revision, "extra": 1}]
        for field, values in {
                "commit": (None, "A1" * 20, "a1" * 19, 1),
                "dirty": (None, 0, "false"),
                "changedPaths": (None, True, -1, 1.0, "1"),
                "stateSha256": (None, "B2" * 32, "b2" * 31, 1)}.items():
            missing = dict(self.revision)
            del missing[field]
            invalid.append(missing)
            invalid.extend({**self.revision, field: value} for value in values)
        for revision in invalid:
            with self.subTest(revision=revision):
                self.write_evidence("toolchain.json", {"revision": revision})
                self.assert_rejected("toolchain.json")
        self.write_evidence("toolchain.json", {})
        self.assert_rejected("toolchain.json")

    def test_revision_change_fails(self):
        for field, value in {"commit": "c3" * 20, "dirty": True, "changedPaths": 1,
                             "stateSha256": "d4" * 32}.items():
            with self.subTest(field=field), mock.patch("check_smoke_evidence.revision_record",
                    return_value={**self.revision, field: value}):
                self.assert_rejected("Revision changed since toolchain record")

    def test_revision_lookup_failure_fails_closed(self):
        with mock.patch("check_smoke_evidence.revision_record", side_effect=ValueError("Git unavailable")):
            self.assert_rejected("Git unavailable")

    def test_rejects_lowercase_hex_secret(self):
        self.assert_secret_rejected("long hex", "a1" * 32)

    def test_rejects_uppercase_hex_secret(self):
        self.assert_secret_rejected("long hex", "A1" * 32)

    def test_rejects_sigv4_authorization(self):
        self.assert_secret_rejected("SigV4", "Authorization: AWS4-HMAC-SHA256 Credential=inert-fixture Signature=" + "a1" * 32)

    def test_rejects_sigv4_signature_without_algorithm(self):
        self.assert_secret_rejected("SigV4", "Signature=" + "a1" * 32)

    def test_long_hex_boundaries_and_threshold(self):
        for text in ("a1" * 16, "A1" * 16, "7" * 32, "sha256:" + "b2" * 32):
            with self.subTest(text=text):
                self.assertIn("long hex", secret_findings(text))
        for boundary in "GgZz+/_-":
            for text in (boundary + "a1" * 32, "a1" * 32 + boundary):
                with self.subTest(text=text):
                    self.assertNotIn("long hex", secret_findings(text))

    def test_only_exact_revision_members_are_exempt_from_long_hex(self):
        for key, length in (("runId", 32), ("commit", 40), ("stateSha256", 64)):
            member = json.dumps({key: "a" * length}, indent=2)
            self.assertEqual(secret_findings(member), [])
            for raw in (json.dumps({key: "A" * length}, indent=2), json.dumps({key + "Other": "a" * length}),
                        json.dumps({key: "a" * (length + 1)}), member.replace(': ', ':'),
                        json.dumps({"detail": "a" * length})):
                with self.subTest(key=key, raw=raw):
                    self.assertIn("long hex", secret_findings(raw))

    def test_other_secret_rules_still_scan_revision_members(self):
        raw = json.dumps({"commit": "a1" * 20, "detail": "Bearer inert-token-fixture"}, indent=2)
        self.assertIn("Bearer token", secret_findings(raw))

    def test_real_writers_pass_secret_rules(self):
        # Writers contain Cookie header templates in source, not emitted evidence.
        root = Path(__file__).resolve().parents[2]
        for name in ("infra/smoke/core_http.py", "infra/smoke/ingress_m29_limits.py", "infra/smoke/media_m29_http.py",
                     "infra/smoke/media_m30_http.py", "infra/smoke/media_m34_faults.py", "web/e2e/m33-vertical-slice.spec.ts"):
            with self.subTest(file=name):
                findings = secret_findings((root / name).read_text(encoding="utf-8"))
                self.assertEqual([rule for rule in findings if rule in ("SigV4", "long hex")], [])

    def test_acceptance_python_writers_propagate_optional_run_identity(self):
        root = Path(__file__).resolve().parents[2]
        for name in ("media_m30_http.py", "ingress_m30_paths.py", "media_m34_faults.py"):
            with self.subTest(file=name):
                tree = ast.parse((root / "infra/smoke" / name).read_text(encoding="utf-8"))
                members = [value for node in ast.walk(tree) if isinstance(node, ast.Dict)
                           for key, value in zip(node.keys, node.values)
                           if key is None and isinstance(value, ast.IfExp)
                           and isinstance(value.body, ast.Dict)
                           and any(isinstance(key, ast.Constant) and key.value == "runId" for key in value.body.keys)]
                self.assertEqual(len(members), 1)
                expression = compile(ast.Expression(members[0]), name, "eval")
                for run_id in (None, "", "fixture-run"):
                    with mock.patch.dict(os.environ, {}, clear=True):
                        if run_id is not None:
                            os.environ["LIBRA_VERIFY_RUN_ID"] = run_id
                        self.assertEqual(eval(expression, {"os": os}), {"runId": run_id} if run_id else {})

    def acceptance_documents(self):
        documents = {name: {"result": result, "runId": self.run_id, "checks": ["Fixture acceptance check"]}
                     for name, result in ACCEPTANCE_EVIDENCE.items()}
        for name, document in documents.items():
            self.write_evidence(name, document)
        return documents

    def test_acceptance_requires_each_additional_file(self):
        for name, document in self.acceptance_documents().items():
            with self.subTest(file=name):
                (self.output / name).unlink()
                self.assert_rejected("Missing smoke evidence: " + name, acceptance=True)
                self.write_evidence(name, document)

    def test_acceptance_requires_valid_result_identity_and_checks(self):
        for name, document in self.acceptance_documents().items():
            for field, values in {"result": (None, "", "WRONG"), "runId": (None, "", "WRONG"),
                                  "checks": (None, [], "check", [""], [" "], [1], ["passed", False])}.items():
                for value in values:
                    with self.subTest(file=name, field=field, value=value):
                        changed = {**document, field: value}
                        if value is None:
                            del changed[field]
                        self.write_evidence(name, changed)
                        self.assert_rejected(name, acceptance=True)
            self.write_evidence(name, document)

    def test_acceptance_requires_fresh_evidence_against_each_jar(self):
        for name, document in self.acceptance_documents().items():
            for jar in self.jars:
                for difference in (-1_000_000_000, 0):
                    with self.subTest(file=name, jar=jar.name, difference=difference):
                        newer = self.jar_time + 2_000_000_000
                        os.utime(jar, ns=(newer, newer))
                        os.utime(self.output / name, ns=(newer + difference, newer + difference))
                        self.assert_rejected("Stale smoke evidence: " + name, acceptance=True)
                        os.utime(jar, ns=(self.jar_time, self.jar_time))
                        self.write_evidence(name, document)

    def test_acceptance_cli_writes_all_required_files(self):
        self.acceptance_documents()
        with redirect_stdout(io.StringIO()):
            main(["--root", str(self.root), "--acceptance"])
        summary = json.loads(self.summary.read_text(encoding="utf-8"))
        self.assertEqual(summary["result"], "SMOKE_EVIDENCE_PASS")
        self.assertEqual(set(summary["files"]), set(EVIDENCE) | set(ACCEPTANCE_EVIDENCE))

    def test_standard_gate_does_not_require_acceptance_files(self):
        self.assertEqual(set(collect(self.root)["files"]), set(EVIDENCE))

    def test_happy_path_with_real_driver_check_strings_and_revision_sha256(self):
        # CHECKS messages copied verbatim from passed() in infra/smoke/core_http.py,
        # infra/smoke/media_m29_http.py and infra/smoke/ingress_m29_limits.py.
        # The Media f-string uses fixture counts for segments and delivered bytes.
        checks = {
            "smoke.json": [
                "Packaged Core health through repository Nginx ingress",
                "Fresh Flyway migrations and separate database owners",
                "Anonymous, CSRF, metrics and public internal-route denial",
                "Registration/login cookies and unverified Premium denial without mutation",
                "Scheduled encrypted mail queue to Mailpit and single-use email verification",
                "Simulated Premium idempotency, profile creation, fallback and USER denial",
                "ADMIN operations/metrics, cross-account profile denial, unavailable statistics",
                "Non-READY publication denial, synthetic Kafka Media projection and explicit publication",
                "Watchlist retry, playback ticket cookie, progress replay/history and unpublication renewal denial",
                "Scheduled outbox ACK and matching record read from real Kafka",
                "Refresh rotation and logout revocation through ingress",
            ],
            "media-m29.json": [
                "Packaged Core and Media healthy behind the repository Nginx ingress",
                "ADMIN upload through Core and Media control, signed PUT to private SeaweedFS, durable job queued",
                "Media worker probe/transcode/finalize to READY, outbox to real Kafka, Core projection of the candidate",
                "Explicit publication of the READY binding",
                "Verified Premium viewer admitted; Core's own signer set the __Secure- ticket on the session path",
                "Master, rendition and all 3 segments (12345 bytes) served whole through Nginx",
                "ffmpeg decoded the downloaded HLS and played it directly through Nginx with only the ticket cookie "
                "(H.264 + AAC, 12 s)",
                "No cookie, bearer header, wrong cookie name, identity cookies alone and another session's ticket refused; "
                "non-Premium admission refused without a ticket",
                "Malformed stream paths answered 404 by Nginx without reaching Media; internal and storage paths 404",
                "Renewal reissued the ticket cookie and the renewed ticket plays",
            ],
            "ingress-m29.json": [
                "Stream location forwards /v1/streams/{sessionId}/{name} and the ticket cookie unchanged",
                "Media's 503 with Retry-After passes through uninterrupted",
                "Malformed stream paths (including .html and .jpg names) are a 404 problem from the ingress and never "
                "reach Media",
                "Methods other than GET and requests announcing a body are refused before the stream limits, so an "
                "unsent body cannot hold a session's streams",
                "A fifth concurrent stream of one session is refused at once with 503 and Retry-After: 1; "
                "the share returns afterwards",
                "A seventeenth concurrent stream from one client address is refused with the same 503 problem",
            ],
        }
        for name, messages in checks.items():
            self.write_evidence(name, {**self.documents[name], "checks": messages, "stateSha256": "a1" * 32})
        summary = collect(self.root)
        self.assertEqual(summary["result"], "SMOKE_EVIDENCE_PASS")
        self.assertEqual(summary["files"], {
            name: {"checkCount": len(messages), "newerThanJars": True}
            for name, messages in checks.items()
        })


if __name__ == "__main__":
    unittest.main()
