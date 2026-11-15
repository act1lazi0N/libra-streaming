"""Require passing Maven suites and report freshness after the run start; export counts only."""
import argparse
import json
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET


# Real-dependency Media suites: real ffprobe, real FFmpeg, real sockets, Testcontainers PostgreSQL/Kafka/storage.
# Fully qualified; the gate fails if any is missing or not passing.
REQUIRED_MEDIA_SUITES = (
    "com.libra.streaming.media.processing.infrastructure.FfprobeFixtureIntegrationTest",
    "com.libra.streaming.media.processing.infrastructure.FfmpegTranscodeIntegrationTest",
    "com.libra.streaming.media.delivery.ProtectedStreamServerIntegrationTest",
    "com.libra.streaming.media.storage.MediaStorageIntegrationTest",
)
# Analytics suites that need a real PostgreSQL (Testcontainers): migration and transactions, the service-JWT chain,
# the refusal matrix, and startup/outage behavior. An in-memory database cannot stand in for any of them.
REQUIRED_ANALYTICS_SUITES = (
    "com.libra.streaming.recommendation.freshness.infrastructure.AnalyticsPersistenceIntegrationTest",
    "com.libra.streaming.recommendation.internalapi.infrastructure.CoreServiceSecurityIntegrationTest",
    "com.libra.streaming.recommendation.internalapi.infrastructure.CoreServiceRejectionIntegrationTest",
    "com.libra.streaming.recommendation.AnalyticsStartupIntegrationTest",
)
REQUIRED_SUITES = {"media": REQUIRED_MEDIA_SUITES, "recommendation-analytics": REQUIRED_ANALYTICS_SUITES}
# Every backend module must bring integration evidence; a module that is absent or has none fails the gate.
REQUIRED_MODULES = ("core", "media", "recommendation-analytics")
LABELS = {"core": "Core", "media": "Media", "recommendation-analytics": "Analytics"}


def collect(root):
    started_at = os.environ.get("LIBRA_VERIFY_STARTED_AT", "")
    if started_at and not re.fullmatch(r"[0-9]+", started_at):
        raise ValueError("Malformed run start: LIBRA_VERIFY_STARTED_AT")
    reports = {}
    sources = {}
    for module in sorted((root / "services").iterdir()):
        for source in sorted((module / "src/test/java").rglob("*Test.java")):
            package = re.search(r"^package\s+([\w.]+);", source.read_text(encoding="utf-8"), re.M)
            if not package:
                raise ValueError(f"Missing test package: {source.name}")
            name = f"{package[1]}.{source.stem}"
            sources.setdefault(module.name, set()).add(name)
            lane = "failsafe" if source.stem.endswith("IntegrationTest") else "surefire"
            report = module / f"target/{lane}-reports/TEST-{name}.xml"
            if not report.is_file():
                raise ValueError(f"Missing {lane} report: {name}")
            reports[report] = (module.name, report.parent.name.removesuffix("-reports"))
        for lane in ("surefire", "failsafe"):
            directory = module / f"target/{lane}-reports"
            for report in sorted(directory.rglob("TEST-*.xml")):
                reports[report] = (module.name, directory.name.removesuffix("-reports"))
    rows = []
    for report, (module, lane) in reports.items():
        if started_at and report.stat().st_mtime_ns <= int(started_at):
            raise ValueError(f"Stale report: {report.name}")
        name = report.name[len("TEST-"):-len(".xml")]
        suite = ET.parse(report).getroot()
        counts = {key: int(suite.attrib[key]) for key in ("tests", "failures", "errors", "skipped")}
        cases = suite.findall("testcase")
        if counts["tests"] < 1 or len(cases) != counts["tests"]:
            raise ValueError(f"Empty or incomplete suite: {name}")
        if any(counts[key] for key in ("failures", "errors", "skipped")) or any(
                case.find(tag) is not None for case in cases for tag in ("failure", "error", "skipped")):
            raise ValueError(f"Failed or skipped suite: {name}")
        rows.append({"module": module, "lane": lane, "suite": name, **counts})
    for module, names in REQUIRED_SUITES.items():
        for name in names:
            if name not in sources.get(module, ()):
                raise ValueError(f"Missing required {LABELS[module]} suite source: {name}")
    for module in REQUIRED_MODULES:
        if not any(row["lane"] == "failsafe" and row["module"] == module for row in rows):
            raise ValueError(f"No {LABELS[module]} integration evidence")
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args(argv)
    output = args.root / "target/verification"
    output.mkdir(parents=True, exist_ok=True)
    # Remove only this tool's previous result, so a failure cannot retain a stale PASS.
    summary = output / "tests.json"
    summary.unlink(missing_ok=True)
    rows = collect(args.root)
    modules = {}
    for row in rows:
        counts = modules.setdefault(row["module"], {}).setdefault(row["lane"], {"suites": 0, "tests": 0})
        counts["suites"] += 1
        counts["tests"] += row["tests"]
    evidence = {
        "result": "TESTS_PASS",
        "suites": rows, "tests": sum(r["tests"] for r in rows), "modules": modules
    }
    if os.environ.get("LIBRA_VERIFY_RUN_ID") and os.environ.get("LIBRA_VERIFY_STARTED_AT"):
        evidence.update(runId=os.environ["LIBRA_VERIFY_RUN_ID"], startedAt=os.environ["LIBRA_VERIFY_STARTED_AT"])
    summary.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(f"PASS: {len(rows)} Maven suites, {sum(r['tests'] for r in rows)} tests, zero failures/errors/skips")


if __name__ == "__main__":
    main()
