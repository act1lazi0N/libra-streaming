import tempfile
from pathlib import Path
import unittest

from check_test_reports import collect


class ReportGateTest(unittest.TestCase):
    def test_requires_executed_successful_integration_suite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "services/core/src/test/java/example/BoundaryIntegrationTest.java"
            source.parent.mkdir(parents=True)
            source.write_text("package example;", encoding="utf-8")
            report = root / "services/core/target/failsafe-reports/TEST-example.BoundaryIntegrationTest.xml"
            with self.assertRaisesRegex(ValueError, "Missing failsafe"):
                collect(root)
            report.parent.mkdir(parents=True)
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
                        collect(root)
            report.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase/></testsuite>', encoding="utf-8")
            self.assertEqual(collect(root)[0]["tests"], 1)


if __name__ == "__main__":
    unittest.main()
