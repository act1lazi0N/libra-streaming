"""Validate all versioned contracts, including repository-local reference targets."""
import json
from pathlib import Path
from urllib.parse import unquote, urlsplit

import yaml
from jsonschema import Draft202012Validator
from openapi_spec_validator import validate_url

ROOT = Path(__file__).resolve().parents[2]
CONTRACTS = ROOT / "contracts"


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


def main():
    output = ROOT / "target/verification/contracts.json"
    output.unlink(missing_ok=True)
    documents = {path.resolve(): yaml.safe_load(path.read_text(encoding="utf-8"))
                 for path in sorted(CONTRACTS.rglob("*")) if path.suffix in (".json", ".yaml")}
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
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps({"validated": [p.relative_to(ROOT).as_posix() for p in documents]}, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
