"""Validate all versioned contracts, including repository-local reference targets."""
import json
import re
from pathlib import Path
from urllib.parse import unquote, urlsplit

import yaml
from jsonschema import Draft202012Validator
from openapi_spec_validator import validate_url
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts"
EXAMPLES = CONTRACTS / "events/examples"
FIXTURES = CONTRACTS / "fixtures"
EXPECTATIONS = ("valid", "schema-invalid", "semantic-invalid")
PLACEHOLDER = re.compile(r"\{\{([A-Za-z][A-Za-z0-9]*)\}\}")
# Format assertions are opt-in in JSON Schema 2020-12; a missing checker would silently pass every value.
REQUIRED_FORMATS = ("date", "date-time", "uuid")
# Wire examples are templates: placeholders stand for the values each test substitutes.
PLACEHOLDERS = {
    "{{eventId}}": "11111111-1111-4111-8111-111111111111",
    "{{assetId}}": "22222222-2222-4222-8222-222222222222",
    "{{contentId}}": "33333333-3333-4333-8333-333333333333",
    "{{bindingId}}": "44444444-4444-4444-8444-444444444444",
    "{{correlationId}}": "55555555-5555-4555-8555-555555555555",
    "{{occurredAt}}": "2026-10-12T00:00:00.123456Z",
}


def references(value):
    if isinstance(value, dict):
        for key, child in value.items():
            if key == "$ref":
                yield child
            else:
                yield from references(child)
    elif isinstance(value, list):
        for child in value:
            yield from references(child)


def load_documents():
    return {path.resolve(): yaml.safe_load(path.read_text(encoding="utf-8"))
            for path in sorted(CONTRACTS.rglob("*"))
            if path.suffix in (".json", ".yaml") and not path.is_relative_to(EXAMPLES)
            and not path.is_relative_to(FIXTURES)}


def instantiate(text, placeholders, name):
    unknown = sorted({key for key in PLACEHOLDER.findall(text) if key not in placeholders})
    if unknown:
        raise ValueError(f"Unknown placeholder {unknown} in {name}")
    text = PLACEHOLDER.sub(lambda match: placeholders[match.group(1)], text)
    if "{{" in text:
        raise ValueError(f"Malformed placeholder in {name}")
    return json.loads(text)


def fixture_validator(documents, contract, pointer):
    registry = Registry().with_resources(
        (path.as_uri(), Resource.from_contents(doc, default_specification=DRAFT202012))
        for path, doc in documents.items())
    target = (CONTRACTS / contract).resolve()
    if target not in documents:
        raise ValueError(f"Unknown fixture contract {contract}")
    if pointer and not pointer.startswith("#/"):
        raise ValueError(f"Unsupported fixture pointer {pointer}")
    # An OpenAPI document is not itself a schema: without a pointer every instance would validate vacuously.
    if contract.startswith("http/") and not (pointer or "").startswith("#/components/schemas/"):
        raise ValueError(f"HTTP fixture contract {contract} needs a #/components/schemas/ pointer")
    checker = Draft202012Validator.FORMAT_CHECKER
    missing = [name for name in REQUIRED_FORMATS if name not in checker.checkers]
    if missing:
        raise ValueError(f"Format checkers unavailable: {missing}")
    return Draft202012Validator({"$ref": target.as_uri() + (pointer or "")}, registry=registry, format_checker=checker)


def check_fixtures(documents, manifest_path):
    """Valid and semantic-invalid fixtures must pass the schema; schema-invalid ones must fail it."""
    base = manifest_path.parent.resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    placeholders = manifest["placeholders"]
    entries = manifest["fixtures"]
    if not entries or len({entry["id"] for entry in entries}) != len(entries):
        raise ValueError(f"Fixture IDs must be present and unique in {manifest_path.name}")
    listed = set()
    for entry in entries:
        if entry["expect"] not in EXPECTATIONS or not entry.get("rule"):
            raise ValueError(f"Fixture {entry['id']} needs a known expectation and a rule")
        path = (base / entry["file"]).resolve()
        if not path.is_relative_to(base) or not path.is_file() or path in listed:
            raise ValueError(f"Fixture {entry['id']} file is missing, outside or listed twice")
        listed.add(path)
        instance = instantiate(path.read_text(encoding="utf-8"), placeholders, entry["file"])
        errors = list(fixture_validator(documents, entry["contract"], entry.get("pointer")).iter_errors(instance))
        if (entry["expect"] == "schema-invalid") != bool(errors):
            detail = errors[0].message if errors else "no schema error"
            raise ValueError(f"Fixture {entry['id']} expected {entry['expect']}: {detail}")
        if entry["expect"] == "schema-invalid":
            # Pin the reason, so a fixture cannot keep "failing" after its intended violation stops being one.
            expected = entry.get("schemaError") or {}
            actual = [{"keyword": error.validator, "path": "/" + "/".join(str(part) for part in error.absolute_path)}
                      for error in errors]
            if actual != [{"keyword": expected.get("keyword"), "path": expected.get("path")}]:
                raise ValueError(f"Fixture {entry['id']} failed for {actual}, not its schemaError {expected}")
    unlisted = sorted(path.relative_to(base).as_posix() for path in base.rglob("*")
                      if path.is_file() and path.resolve() != manifest_path.resolve() and path.resolve() not in listed)
    if unlisted:
        raise ValueError(f"Fixtures not in {manifest_path.name}: {unlisted}")
    return sorted(listed)


def main():
    output = ROOT / "target/verification/contracts.json"
    output.unlink(missing_ok=True)
    documents = load_documents()
    if not documents:
        raise ValueError("No contracts found")
    for path, doc in documents.items():
        for ref in references(doc):
            parts = urlsplit(ref)
            if parts.scheme or parts.netloc or parts.query:
                raise ValueError(f"Non-local reference in {path.name}")
            target = (path.parent / unquote(parts.path)).resolve() if parts.path else path
            if not target.is_relative_to(CONTRACTS.resolve()) or target not in documents:
                raise ValueError(f"Missing/outside contract reference in {path.name}")
            value = documents[target]
            fragment = unquote(parts.fragment)
            if fragment and not fragment.startswith("/"):
                raise ValueError(f"Unsupported reference anchor in {path.name}")
            for key in fragment.split("/")[1:]:
                key = key.replace("~1", "/").replace("~0", "~")
                value = value[int(key)] if isinstance(value, list) else value[key]
    # Preflight the entire graph before any validator can follow a reference
    # transitively into another document.
    for path, doc in documents.items():
        if path.parent.name == "http":
            validate_url(path.as_uri())
        else:
            Draft202012Validator.check_schema(doc)
        print(f"PASS: {path.relative_to(ROOT).as_posix()}")
    examples = sorted(EXAMPLES.glob("*.json"))
    for path in examples:
        schema = CONTRACTS / "events" / (path.name.rsplit(".", 2)[0] + ".schema.json")
        text = path.read_text(encoding="utf-8")
        for placeholder, value in PLACEHOLDERS.items():
            text = text.replace(placeholder, value)
        if "{{" in text:
            raise ValueError(f"Unknown placeholder in {path.name}")
        Draft202012Validator(documents[schema.resolve()], format_checker=Draft202012Validator.FORMAT_CHECKER).validate(json.loads(text))
        print(f"PASS: {path.relative_to(ROOT).as_posix()}")
    manifests = sorted(FIXTURES.glob("*/manifest.json"))
    if any(path.is_file() for path in FIXTURES.rglob("*")) and not manifests:
        raise ValueError("Contract fixtures exist without a manifest")
    fixtures = []
    for manifest in manifests:
        checked = check_fixtures(documents, manifest)
        fixtures.extend([manifest.resolve(), *checked])
        print(f"PASS: {manifest.relative_to(ROOT).as_posix()} ({len(checked)} fixtures)")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"validated": [p.relative_to(ROOT).as_posix() for p in [*documents, *examples, *fixtures]]}, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
