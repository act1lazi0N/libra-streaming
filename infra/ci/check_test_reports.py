"""Fail closed on missing/empty/skipped Maven suites; export counts, never raw logs."""
import argparse
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def collect(root):
    rows = []
    for module in sorted((root / "services").iterdir()):
        for source in sorted((module / "src/test/java").rglob("*Test.java")):
            package = re.search(r"^package\s+([\w.]+);", source.read_text(encoding="utf-8"), re.M)
            if not package:
                raise ValueError(f"Missing test package: {source.name}")
            name = f"{package[1]}.{source.stem}"
            lane = "failsafe" if source.stem.endswith("IntegrationTest") else "surefire"
            report = module / f"target/{lane}-reports/TEST-{name}.xml"
            if not report.is_file():
                raise ValueError(f"Missing {lane} report: {name}")
            suite = ET.parse(report).getroot()
            counts = {key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
            cases = suite.findall("testcase")
            if counts["tests"] < 1 or len(cases) != counts["tests"]:
                raise ValueError(f"Empty or incomplete suite: {name}")
            if any(counts[key] for key in ("failures", "errors", "skipped")) or any(
                    case.find(tag) is not None for case in cases for tag in ("failure", "error", "skipped")):
                raise ValueError(f"Failed or skipped suite: {name}")
            rows.append({"module": module.name, "lane": lane, "suite": name, **counts})
    if not rows or not any(row["lane"] == "failsafe" and row["module"] == "core" for row in rows):
        raise ValueError("No Core integration evidence")
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    output = args.root / "target/verification"
    output.mkdir(parents=True, exist_ok=True)
    # Remove only this tool's previous result, so a failure cannot retain a stale PASS.
    summary = output / "tests.json"
    summary.unlink(missing_ok=True)
    rows = collect(args.root)
    summary.write_text(json.dumps({"suites": rows, "tests": sum(r["tests"] for r in rows)}, indent=2) + "\n", encoding="utf-8")
    print(f"PASS: {len(rows)} Maven suites, {sum(r['tests'] for r in rows)} tests, zero failures/errors/skips")


if __name__ == "__main__":
    main()
