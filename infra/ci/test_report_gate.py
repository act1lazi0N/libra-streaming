from contextlib import redirect_stdout
import io
import json
from pathlib import Path
import re
import tempfile
import unittest

from check_test_reports import REQUIRED_MEDIA_SUITES, collect, main


class ReportGateTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.add_suite("core", "example.BoundaryIntegrationTest")
        self.add_suite("core", "example.UnitTest", tests=2)
        self.add_suite("media", "example.MediaUnitTest", tests=3)
        for name in REQUIRED_MEDIA_SUITES:
            self.add_suite("media", name)

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
        self.source("core", "example.BoundaryIntegrationTest").unlink()
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
        self.assertEqual(summary["tests"], 10)
        self.assertEqual(summary["modules"], {
            "core": {"failsafe": {"suites": 1, "tests": 1}, "surefire": {"suites": 1, "tests": 2}},
            "media": {"failsafe": {"suites": 4, "tests": 4}, "surefire": {"suites": 1, "tests": 3}},
        })

    def test_failure_removes_previous_summary(self):
        summary = self.root / "target/verification/tests.json"
        summary.parent.mkdir(parents=True)
        summary.write_text('{}', encoding="utf-8")
        self.report("media", REQUIRED_MEDIA_SUITES[0]).unlink()
        with self.assertRaises(ValueError):
            main(["--root", str(self.root)])
        self.assertFalse(summary.exists())


if __name__ == "__main__":
    unittest.main()
