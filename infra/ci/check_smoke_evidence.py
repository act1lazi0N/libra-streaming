"""Gate sanitized smoke and acceptance evidence by revision provenance and run freshness."""
import argparse
import json
import os
from pathlib import Path
import re

from record_toolchain import revision_record


EVIDENCE = {
    "smoke.json": None,
    "ingress-m29.json": "INGRESS_M29_PASS",
    "media-m29.json": "MEDIA_M29_SMOKE_PASS",
}
ACCEPTANCE_EVIDENCE = {
    "media-m30.json": "MEDIA_M30_SMOKE_PASS",
    "ingress-m30.json": "INGRESS_M30_PATHS_PASS",
    "media-m33.json": "MEDIA_M33_BROWSER_PASS",
    "media-m34.json": "MEDIA_M34_FAULTS_PASS",
    "media-m36.json": "MEDIA_M36_RESTART_PASS",
}
REVISION_MEMBERS = re.compile(r'"(?:runId": "[0-9a-f]{32}|commit": "[0-9a-f]{40}|stateSha256": "[0-9a-f]{64})"')
SECRET_RULES = (
    ("PEM", re.compile(r"-----BEGIN")),
    # Require at least 8 token characters; the writer's prose "bearer header," has only 6.
    ("Bearer token", re.compile(r"\bBearer\s+[A-Za-z0-9._~+/-]{8,}", re.I)),
    ("JWT", re.compile(r"\beyJ[A-Za-z0-9_-]*\.eyJ[A-Za-z0-9_-]*")),
    ("Cookie header", re.compile(r"\b(?:Set-Cookie|Cookie)\s*:", re.I)),
    ("Presigned URL", re.compile(r"X-Amz-(Signature|Credential|Security-Token)=", re.I)),
    ("SigV4", re.compile(r"AWS4-HMAC-SHA256|Signature=[0-9a-f]{64}")),
    ("long hex", re.compile(r"(?<![A-Za-z0-9+/_-])(?:[0-9a-f]{32,}|[0-9A-F]{32,})(?![A-Za-z0-9+/_-])")),
    # Match whole runs: require mixed case and a digit; lowercase slash paths are excluded.
    # Uniform-case hex digests (including uppercase hex) cannot satisfy mixed case.
    ("long base64", re.compile(
        r"(?<![A-Za-z0-9+/_-])(?=[A-Za-z0-9+/_-]*[A-Z])"
        r"(?=[A-Za-z0-9+/_-]*[a-z])(?=[A-Za-z0-9+/_-]*[0-9])"
        r"[A-Za-z0-9+/_-]{40,}={0,2}(?![A-Za-z0-9+/_=-])")),
)


def secret_findings(raw):
    return [rule for rule, pattern in SECRET_RULES
            if pattern.search(REVISION_MEMBERS.sub("", raw) if rule == "long hex" else raw)]


def collect(root, acceptance=False):
    output = root / "target/verification/smoke-gate.json"
    # Sanitize every publishable JSON before any validation can fail. Delete all
    # offending files, retaining only a rule and filename for the diagnostic.
    findings = []
    for path in sorted(output.parent.glob("*.json")):
        try:
            raw = path.read_bytes().decode("utf-8", errors="replace")
        except OSError:
            findings.append(f"Unable to read evidence: {path.name}")
            continue
        for rule in secret_findings(raw):
            findings.append(f"{rule}: {path.name}")
            try:
                path.unlink()
            except OSError:
                findings.append(f"Unable to remove evidence: {path.name}")
            break
    # A failed run must never leave this gate's previous PASS behind.
    output.unlink(missing_ok=True)
    if findings:
        raise ValueError("; ".join(findings))
    run_id = os.environ.get("LIBRA_VERIFY_RUN_ID")
    if not run_id:
        raise ValueError("Missing run identity: LIBRA_VERIFY_RUN_ID")
    started_at = os.environ.get("LIBRA_VERIFY_STARTED_AT", "")
    if not re.fullmatch(r"[0-9]+", started_at):
        raise ValueError("Missing or malformed run start: LIBRA_VERIFY_STARTED_AT")
    jars = [root / f"services/{module}/target/{module}-service-0.1.0-SNAPSHOT.jar"
            for module in ("core", "media")]
    for jar in jars:
        if not jar.is_file():
            raise ValueError(f"Missing packaged JAR: {jar.name}")
        if jar.stat().st_mtime_ns <= int(started_at):
            raise ValueError(f"JAR not built in this run: {jar.name}")
    newest_jar = max(jar.stat().st_mtime_ns for jar in jars)
    toolchain = output.parent / "toolchain.json"
    if not toolchain.is_file():
        raise ValueError("Missing toolchain evidence: toolchain.json")
    if toolchain.stat().st_mtime_ns <= newest_jar:
        raise ValueError("Stale toolchain evidence: toolchain.json")
    tools = read_evidence(toolchain)
    revision = tools.get("revision")
    if (not isinstance(revision, dict) or set(revision) != {"commit", "dirty", "changedPaths", "stateSha256"}
            or not isinstance(revision.get("commit"), str)
            or not re.fullmatch(r"[0-9a-f]{40}", revision["commit"])
            or type(revision.get("dirty")) is not bool
            or type(revision.get("changedPaths")) is not int or revision["changedPaths"] < 0
            or not isinstance(revision.get("stateSha256"), str)
            or not re.fullmatch(r"[0-9a-f]{64}", revision["stateSha256"])):
        raise ValueError("Malformed revision: toolchain.json")
    if revision_record(root) != revision:
        raise ValueError("Revision changed since toolchain record")
    tests = output.parent / "tests.json"
    if not tests.is_file():
        raise ValueError("Missing test evidence: tests.json")
    test_evidence = read_evidence(tests)
    if (test_evidence.get("result") != "TESTS_PASS" or test_evidence.get("runId") != run_id
            or test_evidence.get("startedAt") != started_at):
        raise ValueError("Invalid test evidence: tests.json")
    files = {}
    required = {**EVIDENCE, **ACCEPTANCE_EVIDENCE} if acceptance else EVIDENCE
    for name, expected_result in required.items():
        path = output.parent / name
        if not path.is_file():
            raise ValueError(f"Missing smoke evidence: {name}")
        evidence = read_evidence(path)
        if evidence.get("runId") != run_id:
            raise ValueError(f"Run identity mismatch: {name}")
        if expected_result is not None and evidence.get("result") != expected_result:
            raise ValueError(f"Invalid smoke result: {name}")
        checks = evidence.get("checks")
        if not isinstance(checks, list) or not checks or any(
                not isinstance(check, str) or not check.strip() for check in checks):
            raise ValueError(f"Invalid or empty smoke checks: {name}")
        if path.stat().st_mtime_ns <= newest_jar:
            raise ValueError(f"Stale smoke evidence: {name}")
        files[name] = {"checkCount": len(checks), "newerThanJars": True}
    summary = {"result": "SMOKE_EVIDENCE_PASS", "files": files}
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    return summary


def read_evidence(path):
    try:
        raw = path.read_text(encoding="utf-8")
    except UnicodeError:
        raise ValueError(f"Invalid UTF-8 smoke evidence: {path.name}") from None
    try:
        evidence = json.loads(raw)
    except ValueError:
        raise ValueError(f"Invalid JSON smoke evidence: {path.name}") from None
    if not isinstance(evidence, dict):
        raise ValueError(f"Smoke evidence must be a JSON object: {path.name}")
    return evidence


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--acceptance", action="store_true")
    args = parser.parse_args(argv)
    collect(args.root, acceptance=args.acceptance)
    print("PASS: fresh sanitized Core and Media smoke evidence")


if __name__ == "__main__":
    main()
