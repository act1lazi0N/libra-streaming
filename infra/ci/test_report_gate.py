from contextlib import redirect_stdout
import io
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import unittest
from unittest import mock

import check_test_reports
from check_test_reports import REQUIRED_ANALYTICS_SUITES, REQUIRED_CORE_SUITES, REQUIRED_MEDIA_SUITES, collect, main

ANALYTICS = "recommendation-analytics"


class ReportGateTest(unittest.TestCase):
    def setUp(self):
        self.enterContext(mock.patch.dict(os.environ, {"LIBRA_VERIFY_STARTED_AT": "", "LIBRA_VERIFY_RUN_ID": ""}))
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.add_suite("core", "example.BoundaryIntegrationTest")
        for name in REQUIRED_CORE_SUITES:
            self.add_suite("core", name)
        self.add_suite("core", "example.UnitTest", tests=2)
        self.add_suite("media", "example.MediaUnitTest", tests=3)
        for name in REQUIRED_MEDIA_SUITES:
            self.add_suite("media", name)
        self.add_suite(ANALYTICS, "example.AnalyticsUnitTest", tests=2)
        for name in REQUIRED_ANALYTICS_SUITES:
            self.add_suite(ANALYTICS, name)

    def source(self, module, name):
        return self.root / f"services/{module}/src/test/java/{name.replace('.', '/')}.java"

    def report(self, module, name):
        lane = "failsafe" if name.endswith("IntegrationTest") else "surefire"
        return self.root / f"services/{module}/target/{lane}-reports/TEST-{name}.xml"

    def add_suite(self, module, name, tests=1):
        source = self.source(module, name)
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(f"package {name.rsplit('.', 1)[0]};", encoding="utf-8")
        report = self.report(module, name)
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text(f'<testsuite tests="{tests}" failures="0" errors="0" skipped="0">'
                          + '<testcase/>' * tests + '</testsuite>', encoding="utf-8")

    def test_requires_executed_successful_integration_suite(self):
        report = self.report("core", "example.BoundaryIntegrationTest")
        report.unlink()
        with self.assertRaisesRegex(ValueError, "Missing failsafe"):
            collect(self.root)
        for counts, body in [
            ('tests="0" failures="0" errors="0" skipped="0"', ''),
            ('tests="1" failures="0" errors="0" skipped="1"', '<testcase><skipped/></testcase>'),
            ('tests="1" failures="1" errors="0" skipped="0"', '<testcase><failure/></testcase>'),
            ('tests="1" failures="0" errors="1" skipped="0"', '<testcase><error/></testcase>'),
            ('tests="1" failures="0" errors="0" skipped="0"', ''),
            ('tests="1" failures="0" errors="0" skipped="0"', '<testcase><skipped/></testcase>'),
        ]:
            with self.subTest(counts=counts, body=body):
                report.write_text(f'<testsuite {counts}>{body}</testsuite>', encoding="utf-8")
                with self.assertRaises(ValueError):
                    collect(self.root)
        self.add_suite("core", "example.BoundaryIntegrationTest")
        self.assertEqual(collect(self.root)[0]["tests"], 1)

    def test_requires_core_failsafe_suite(self):
        for name in ("example.BoundaryIntegrationTest", *REQUIRED_CORE_SUITES):
            self.source("core", name).unlink()
            self.report("core", name).unlink()
        with mock.patch.object(check_test_reports, "REQUIRED_SUITES", {"core": ()}):
            with self.assertRaisesRegex(ValueError, "No Core integration evidence"):
                collect(self.root)

    def test_requires_media_failsafe_reports(self):
        for name in REQUIRED_MEDIA_SUITES:
            self.report("media", name).unlink()
        with self.assertRaisesRegex(ValueError, "Missing failsafe"):
            collect(self.root)

    def test_requires_each_media_suite_source(self):
        for name in REQUIRED_MEDIA_SUITES:
            with self.subTest(suite=name):
                self.source("media", name).unlink()
                with self.assertRaisesRegex(ValueError, re.escape(name)):
                    collect(self.root)
                self.add_suite("media", name)

    def test_requires_each_media_suite_report(self):
        for name in REQUIRED_MEDIA_SUITES:
            with self.subTest(suite=name):
                self.report("media", name).unlink()
                with self.assertRaisesRegex(ValueError, re.escape(name)):
                    collect(self.root)
                self.add_suite("media", name)

    def test_requires_each_media_suite_to_pass(self):
        for name in REQUIRED_MEDIA_SUITES:
            with self.subTest(suite=name):
                self.report("media", name).write_text(
                    '<testsuite tests="1" failures="1" errors="0" skipped="0">'
                    '<testcase><failure/></testcase></testsuite>', encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "Failed or skipped suite: " + re.escape(name)):
                    collect(self.root)
                self.add_suite("media", name)

    def test_requires_analytics_module(self):
        shutil.rmtree(self.root / "services" / ANALYTICS)
        with self.assertRaisesRegex(ValueError, "Missing required Analytics suite source"):
            collect(self.root)

    def test_analytics_unit_tests_alone_are_not_integration_evidence(self):
        # A context test on an in-memory database is a *Test in the surefire lane: it never satisfies the gate.
        self.add_suite(ANALYTICS, "example.InMemoryContextTest", tests=5)
        for name in REQUIRED_ANALYTICS_SUITES:
            self.source(ANALYTICS, name).unlink()
            self.report(ANALYTICS, name).unlink()
        with self.assertRaisesRegex(ValueError, "Missing required Analytics suite source"):
            collect(self.root)

    def test_requires_each_analytics_suite_source_report_and_pass(self):
        for name in REQUIRED_ANALYTICS_SUITES:
            with self.subTest(suite=name, case="source"):
                self.source(ANALYTICS, name).unlink()
                with self.assertRaisesRegex(ValueError, "Analytics suite source: " + re.escape(name)):
                    collect(self.root)
                self.add_suite(ANALYTICS, name)
            with self.subTest(suite=name, case="report"):
                self.report(ANALYTICS, name).unlink()
                with self.assertRaisesRegex(ValueError, "Missing failsafe report: " + re.escape(name)):
                    collect(self.root)
                self.add_suite(ANALYTICS, name)
            for counts, body in (('failures="1" errors="0" skipped="0"', "<testcase><failure/></testcase>"),
                                 ('failures="0" errors="1" skipped="0"', "<testcase><error/></testcase>"),
                                 ('failures="0" errors="0" skipped="1"', "<testcase><skipped/></testcase>")):
                with self.subTest(suite=name, case=counts):
                    self.report(ANALYTICS, name).write_text(
                        f'<testsuite tests="1" {counts}>{body}</testsuite>', encoding="utf-8")
                    with self.assertRaisesRegex(ValueError, "Failed or skipped suite: " + re.escape(name)):
                        collect(self.root)
                    self.add_suite(ANALYTICS, name)

    def test_requires_each_core_suite_source_report_and_pass(self):
        for name in REQUIRED_CORE_SUITES:
            with self.subTest(suite=name, case="source"):
                self.source("core", name).unlink()
                with self.assertRaisesRegex(ValueError, "Core suite source: " + re.escape(name)):
                    collect(self.root)
                self.add_suite("core", name)
            with self.subTest(suite=name, case="report"):
                self.report("core", name).unlink()
                with self.assertRaisesRegex(ValueError, "Missing failsafe report: " + re.escape(name)):
                    collect(self.root)
                self.add_suite("core", name)
            for counts, body in (('failures="1" errors="0" skipped="0"', "<testcase><failure/></testcase>"),
                                 ('failures="0" errors="1" skipped="0"', "<testcase><error/></testcase>"),
                                 ('failures="0" errors="0" skipped="1"', "<testcase><skipped/></testcase>")):
                with self.subTest(suite=name, case=counts):
                    self.report("core", name).write_text(
                        f'<testsuite tests="1" {counts}>{body}</testsuite>', encoding="utf-8")
                    with self.assertRaisesRegex(ValueError, "Failed or skipped suite: " + re.escape(name)):
                        collect(self.root)
                    self.add_suite("core", name)

    def test_requires_surefire_report(self):
        self.report("core", "example.UnitTest").unlink()
        with self.assertRaisesRegex(ValueError, "Missing surefire"):
            collect(self.root)

    def test_happy_path_writes_per_module_counts(self):
        rows = collect(self.root)
        with redirect_stdout(io.StringIO()):
            main(["--root", str(self.root)])
        summary = json.loads((self.root / "target/verification/tests.json").read_text(encoding="utf-8"))
        self.assertEqual(summary["suites"], rows)
        self.assertEqual(summary["tests"], 33)
        self.assertEqual(summary["result"], "TESTS_PASS")
        self.assertNotIn("runId", summary)
        self.assertNotIn("startedAt", summary)
        self.assertEqual(summary["modules"], {
            "core": {"failsafe": {"suites": 5, "tests": 5}, "surefire": {"suites": 1, "tests": 2}},
            "media": {"failsafe": {"suites": 4, "tests": 4}, "surefire": {"suites": 1, "tests": 3}},
            ANALYTICS: {"failsafe": {"suites": 17, "tests": 17}, "surefire": {"suites": 1, "tests": 2}},
        })

    def test_failure_removes_previous_summary(self):
        summary = self.root / "target/verification/tests.json"
        summary.parent.mkdir(parents=True)
        summary.write_text('{}', encoding="utf-8")
        self.report("media", REQUIRED_MEDIA_SUITES[0]).unlink()
        with self.assertRaises(ValueError):
            main(["--root", str(self.root)])
        self.assertFalse(summary.exists())

    def test_malformed_run_start_fails(self):
        for value in ("abc", "-1", "1.0", " 1", "1\n"):
            with self.subTest(value=value), mock.patch.dict(os.environ, {"LIBRA_VERIFY_STARTED_AT": value}):
                with self.assertRaisesRegex(ValueError, "Malformed run start: LIBRA_VERIFY_STARTED_AT"):
                    collect(self.root)

    def test_stale_or_equal_report_fails(self):
        report = self.report("core", "example.UnitTest")
        started = report.stat().st_mtime_ns
        with mock.patch.dict(os.environ, {"LIBRA_VERIFY_STARTED_AT": str(started)}):
            for difference in (-1_000_000_000, 0):
                with self.subTest(difference=difference):
                    for path in self.root.rglob("TEST-*.xml"):
                        os.utime(path, ns=(started + 1_000_000_000, started + 1_000_000_000))
                    os.utime(report, ns=(started + difference, started + difference))
                    with self.assertRaisesRegex(ValueError, "Stale report: TEST-example.UnitTest.xml"):
                        collect(self.root)

    def test_summary_records_run_identity(self):
        with mock.patch.dict(os.environ, {"LIBRA_VERIFY_RUN_ID": "fixture-run", "LIBRA_VERIFY_STARTED_AT": "1"}):
            with redirect_stdout(io.StringIO()):
                main(["--root", str(self.root)])
        summary = json.loads((self.root / "target/verification/tests.json").read_text(encoding="utf-8"))
        self.assertEqual(summary["result"], "TESTS_PASS")
        self.assertEqual(summary["runId"], "fixture-run")
        self.assertEqual(summary["startedAt"], "1")

    def test_unmapped_reports_are_counted_once_in_both_lanes(self):
        for lane in ("surefire", "failsafe"):
            report = self.root / f"services/core/target/{lane}-reports/TEST-example.Dynamic.xml"
            report.parent.mkdir(parents=True, exist_ok=True)
            report.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase/></testsuite>',
                              encoding="utf-8")
        with redirect_stdout(io.StringIO()):
            main(["--root", str(self.root)])
        summary = json.loads((self.root / "target/verification/tests.json").read_text(encoding="utf-8"))
        self.assertEqual(len(summary["suites"]), 31)
        self.assertEqual(summary["tests"], 35)
        self.assertEqual(summary["modules"]["core"], {
            "surefire": {"suites": 2, "tests": 3}, "failsafe": {"suites": 6, "tests": 6}})
        self.assertEqual({r["lane"] for r in summary["suites"] if r["suite"] == "example.Dynamic"},
                         {"surefire", "failsafe"})

    def test_unmapped_reports_enforce_all_failure_rules(self):
        for lane in ("surefire", "failsafe"):
            report = self.root / f"services/core/target/{lane}-reports/TEST-example.Dynamic.xml"
            report.parent.mkdir(parents=True, exist_ok=True)
            for tests, failures, errors, skipped, body in (
                    (0, 0, 0, 0, ""), (1, 0, 0, 0, ""),
                    (1, 1, 0, 0, "<testcase/>"), (1, 0, 1, 0, "<testcase/>"),
                    (1, 0, 0, 1, "<testcase/>"), (1, 0, 0, 0, "<testcase><failure/></testcase>"),
                    (1, 0, 0, 0, "<testcase><error/></testcase>"), (1, 0, 0, 0, "<testcase><skipped/></testcase>")):
                with self.subTest(lane=lane, body=body, counts=(tests, failures, errors, skipped)):
                    report.write_text(f'<testsuite tests="{tests}" failures="{failures}" errors="{errors}" '
                                      f'skipped="{skipped}">{body}</testsuite>', encoding="utf-8")
                    with self.assertRaisesRegex(ValueError, "suite: example.Dynamic"):
                        collect(self.root)
            report.unlink()

    def test_unmapped_report_must_be_fresh(self):
        report = self.root / "services/core/target/surefire-reports/TEST-example.Dynamic.xml"
        report.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase/></testsuite>',
                          encoding="utf-8")
        os.utime(report, ns=(1, 1))
        with mock.patch.dict(os.environ, {"LIBRA_VERIFY_STARTED_AT": "10000000000"}):
            with self.assertRaisesRegex(ValueError, "Stale report: TEST-example.Dynamic.xml"):
                collect(self.root)

    def test_nested_report_is_validated_in_its_lane(self):
        report = self.root / "services/core/target/failsafe-reports/nested/TEST-example.Dynamic.xml"
        report.parent.mkdir(parents=True)
        report.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase/></testsuite>',
                          encoding="utf-8")
        rows = collect(self.root)
        self.assertEqual([row for row in rows if row["suite"] == "example.Dynamic"], [
            {"module": "core", "lane": "failsafe", "suite": "example.Dynamic",
             "tests": 1, "failures": 0, "errors": 0, "skipped": 0}])
        report.write_text('<testsuite tests="1" failures="0" errors="0" skipped="1"><testcase/></testsuite>',
                          encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Failed or skipped suite: example.Dynamic"):
            collect(self.root)


if __name__ == "__main__":
    unittest.main()
