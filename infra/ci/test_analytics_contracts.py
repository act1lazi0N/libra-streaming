"""RA01.1 Core-Analytics V1 compatibility corpus: gate failure paths, coverage and the frozen V1 surface."""
import hashlib
import json
import shutil
import tempfile
import unittest
from pathlib import Path

import yaml

import check_contracts

ROOT = Path(__file__).resolve().parents[2]
CORPUS = ROOT / "contracts/fixtures/analytics-v1"
MANIFEST = json.loads((CORPUS / "manifest.json").read_text(encoding="utf-8"))
PLAYBACK = "events/core-playback-progress.v1.schema.json"
CATALOG = "events/core-catalog-publication.v1.schema.json"
PROFILE = "events/core-profile-lifecycle.v1.schema.json"
HTTP = "http/analytics-internal.v1.json"
SCHEMAS = "#/components/schemas/"


def load(contract):
    return yaml.safe_load((ROOT / "contracts" / contract).read_text(encoding="utf-8"))


def surface(schema):
    """The compatibility-relevant shape: field names, required set and closedness."""
    return {"properties": sorted(schema.get("properties", {})), "required": sorted(schema.get("required", [])),
            "closed": schema.get("additionalProperties") is False}


def event_parts(contract):
    specific = load(contract)["allOf"][1]["properties"]
    event_type = specific["eventType"]
    return sorted(event_type.get("enum", [event_type.get("const")])), specific["payload"]


# Changing any of these breaks a deployed V1 producer or consumer. Do not edit them to make a change pass:
# add a new versioned contract (schemaVersion 2 / /internal/v2) and new fixtures instead.
FROZEN = {
    PLAYBACK: {"types": ["PlaybackProgressAccepted"], "payload": {"properties": [
        "acceptedWatchedMs", "accountId", "assetId", "assetVersion", "contentId", "positionMs", "profileId",
        "qualified", "sequence", "sessionId", "state", "totalWatchedMs"], "closed": True}},
    CATALOG: {"types": ["CatalogAvailabilityChanged", "CatalogPublished", "CatalogUnpublished"], "payload": {
        "properties": ["active", "contentId", "kind", "ordinal", "parentId", "published"], "closed": True}},
    PROFILE: {"types": ["ProfileCreated", "ProfileDeleted", "ProfileUpdated"], "payload": {
        "properties": ["accountId", "profileId"], "closed": True}},
    "RecommendationQuery": {"properties": ["accountId", "limit", "profileId"], "closed": True},
    "CandidateResponse": {"properties": ["contentIds", "profileId", "updatedAt"], "closed": True},
    "StatisticsQuery": {"properties": ["from", "to"], "closed": True},
    "StatisticsResponse": {"properties": ["acceptedWatchedMs", "from", "qualifiedViews", "to", "uniqueViewers",
                                          "updatedAt"], "closed": True},
    "Problem": {"properties": ["code", "correlationId", "detail", "status", "title", "type"], "closed": True},
}


# Canonical SHA-256 of every V1 subschema, so a changed type, format, bound or enum (not only a renamed field) fails.
FROZEN_SHA256 = {
    "events/core-event-envelope.v1.schema.json": "fd8a9c5476f6c17b356c540b9c120ef3103d404cbce8f18c9b3f4c0ec6d6fae8",
    PLAYBACK: "3fb02973cec721a1a800d1e84061b342538e5ca18d11eca19d34edd2669e26c9",
    CATALOG: "317ea5f0988798f5c3b6b3a10e842e5a46c7f7c0dc4a85da90c04d28314afd00",
    PROFILE: "1ebe0202ed28df6f342be778ba87f2ff5037a1eb4ef879e9c41acc40a7ff809e",
    "RecommendationQuery": "2cb1a9225425af670a3f0b99bda07906d77d61e23dca42304f6271f42f4b83ab",
    "CandidateResponse": "babee100230bb642fb97f4b1b867f4b38d0bdcc78f85418e282e5fdb258c3575",
    "StatisticsQuery": "10dbd62a91722f5774b3e68e11daef3bce35f5df266a6e57048a17744aae4407",
    "StatisticsResponse": "dc672bbd8c0e1515d1aa47e6c3d5a28a79bf385f9240013d25bdd1e7973b2d2a",
    "Problem": "64c235672786b0e3412dc91f3ab87efcb1bd6db95bc391d53c6f1fd48b39c915",
    "operations": "eb6f862bc8958406530f00ad1b0972371336bbf5d01a098587450a8354febecb",
    # The catalog event payload embeds these Core catalog schemas; changing them changes the V1 event.
    "catalog-event-references": "2f28ca5eda84dc921263200ed1d4c502b13e424a7ae2eb0d905f147e322a654d",
}


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")).hexdigest()


def v1_subschemas():
    api = load(HTTP)
    catalog = load("http/core-catalog-profiles.v1.json")["components"]["schemas"]
    parts = {name: load(name) for name in ("events/core-event-envelope.v1.schema.json", PLAYBACK, CATALOG, PROFILE)}
    parts.update({name: api["components"]["schemas"][name] for name in (
        "RecommendationQuery", "CandidateResponse", "StatisticsQuery", "StatisticsResponse", "Problem")})
    parts["operations"] = {path: {**{key: item["post"][key] for key in (
        "operationId", "x-required-scope", "parameters", "requestBody")}, "200": item["post"]["responses"]["200"]}
        for path, item in api["paths"].items()}
    parts["catalog-event-references"] = {key: catalog[key] for key in ("Kind", "Revision", "Binding", "Metadata", "Tier")}
    return parts


class AnalyticsContractGateTest(unittest.TestCase):
    def mutated(self, change):
        """Run the gate on a private copy of the corpus after applying change(manifest, directory)."""
        with tempfile.TemporaryDirectory() as temporary:
            copy = Path(temporary) / "analytics-v1"
            shutil.copytree(CORPUS, copy)
            manifest = json.loads((copy / "manifest.json").read_text(encoding="utf-8"))
            change(manifest, copy)
            (copy / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
            check_contracts.check_fixtures(check_contracts.load_documents(), copy / "manifest.json")

    def entry(self, manifest, fixture_id):
        return next(entry for entry in manifest["fixtures"] if entry["id"] == fixture_id)

    def test_corpus_passes_as_committed(self):
        checked = check_contracts.check_fixtures(check_contracts.load_documents(), CORPUS / "manifest.json")
        self.assertEqual(len(MANIFEST["fixtures"]), len(checked))

    def test_mislabelled_expectations_fail_in_both_directions(self):
        for fixture_id, label in (("playback-qualifying", "schema-invalid"), ("candidates-null-id", "valid"),
                                  ("statistics-unique-over-views", "schema-invalid"),
                                  ("catalog-unknown-kind", "semantic-invalid")):
            with self.subTest(fixture_id):
                with self.assertRaisesRegex(ValueError, f"Fixture {fixture_id} expected"):
                    self.mutated(lambda manifest, _: self.entry(manifest, fixture_id).update(expect=label))

    def test_missing_unlisted_duplicate_and_unknown_placeholder_fail(self):
        with self.assertRaisesRegex(ValueError, "missing, outside or listed twice"):
            self.mutated(lambda manifest, _: self.entry(manifest, "profile-created").update(file="events/absent.json"))
        with self.assertRaisesRegex(ValueError, "missing, outside or listed twice"):
            self.mutated(lambda manifest, _: self.entry(manifest, "profile-created").update(file="../manifest.json"))
        with self.assertRaisesRegex(ValueError, "not in manifest.json"):
            self.mutated(lambda _, directory: (directory / "events/orphan.json").write_text("{}", encoding="utf-8"))
        with self.assertRaisesRegex(ValueError, "unique"):
            self.mutated(lambda manifest, _: manifest["fixtures"].append(dict(manifest["fixtures"][0])))
        with self.assertRaisesRegex(ValueError, "Unknown placeholder"):
            self.mutated(lambda manifest, _: manifest["placeholders"].pop("sessionId"))
        with self.assertRaisesRegex(ValueError, "known expectation"):
            self.mutated(lambda manifest, _: self.entry(manifest, "profile-created").update(expect="maybe"))

    def test_every_v1_target_has_valid_and_invalid_fixtures(self):
        targets = {}
        for entry in MANIFEST["fixtures"]:
            targets.setdefault((entry["contract"], entry.get("pointer")), set()).add(entry["expect"])
        expected = [(PLAYBACK, None), (CATALOG, None), (PROFILE, None)] + [
            (HTTP, SCHEMAS + name) for name in ("RecommendationQuery", "CandidateResponse", "StatisticsQuery",
                                                "StatisticsResponse", "Problem")]
        self.assertEqual(sorted(expected, key=str), sorted(targets, key=str))
        for target, expectations in targets.items():
            self.assertIn("valid", expectations, target)
            self.assertIn("schema-invalid", expectations, target)

    def test_every_core_event_type_and_endpoint_is_pinned_to_real_core_output(self):
        for contract in (PLAYBACK, CATALOG, PROFILE):
            types, _ = event_parts(contract)
            produced = set()
            for entry in MANIFEST["fixtures"]:
                if entry["contract"] == contract and entry.get("producer"):
                    self.assertEqual("valid", entry["expect"], entry["id"])
                    produced.add(json.loads((CORPUS / entry["file"]).read_text(encoding="utf-8"))["eventType"])
            self.assertEqual(set(types), produced, contract)
        requests = {entry.get("coreRequest") for entry in MANIFEST["fixtures"] if entry.get("coreRequest")}
        self.assertEqual({"recommendations", "statistics"}, requests)
        producers = [entry["producer"] for entry in MANIFEST["fixtures"] if entry.get("producer")]
        self.assertEqual(len(producers), len(set(producers)))

    def test_every_downstream_body_declares_cores_outcome(self):
        outcomes = {"CandidateResponse": {"ANALYTICS", "STALE", "NO_VISIBLE_CANDIDATES", "INVALID_RESPONSE"},
                    "StatisticsResponse": {"AVAILABLE", "STALE", "INVALID_RESPONSE"}, "Problem": {"UNAVAILABLE"}}
        for entry in MANIFEST["fixtures"]:
            name = (entry.get("pointer") or "").removeprefix(SCHEMAS)
            if name not in outcomes:
                self.assertNotIn("coreOutcome", entry, entry["id"])
                continue
            self.assertIn(entry["coreOutcome"], outcomes[name], entry["id"])
            accepted = entry["coreOutcome"] in ("ANALYTICS", "AVAILABLE", "STALE", "NO_VISIBLE_CANDIDATES")
            if name != "Problem":
                # Core must accept everything the contract allows and reject everything it forbids.
                self.assertEqual(entry["expect"] == "valid", accepted, entry["id"])
            else:
                self.assertIn(entry["httpStatus"], range(400, 600))
                self.assertIn(entry["coreEndpoint"], ("recommendations", "statistics"))
            self.assertEqual(entry["coreOutcome"] == "ANALYTICS", "coreItems" in entry, entry["id"])

    def test_v1_surface_is_frozen(self):
        for contract in (PLAYBACK, CATALOG, PROFILE):
            types, payload = event_parts(contract)
            actual = surface(payload)
            self.assertEqual(FROZEN[contract]["types"], types, contract)
            self.assertEqual(FROZEN[contract]["payload"]["properties"], actual["properties"], contract)
            self.assertEqual(actual["properties"], actual["required"], f"{contract}: every V1 payload field is required")
            self.assertTrue(actual["closed"], contract)
        envelope = load("events/core-event-envelope.v1.schema.json")
        self.assertEqual({"const": 1}, envelope["properties"]["schemaVersion"])
        api = load(HTTP)
        self.assertEqual({"/internal/v1/recommendations/query": "recommendations:read",
                          "/internal/v1/statistics/query": "statistics:read"},
                         {path: item["post"]["x-required-scope"] for path, item in api["paths"].items()})
        for name in ("RecommendationQuery", "CandidateResponse", "StatisticsQuery", "StatisticsResponse", "Problem"):
            actual = surface(api["components"]["schemas"][name])
            self.assertEqual(FROZEN[name]["properties"], actual["properties"], name)
            self.assertEqual(actual["properties"], actual["required"], name)
            self.assertTrue(actual["closed"], name)
        for name, value in v1_subschemas().items():
            self.assertEqual(FROZEN_SHA256[name], digest(value),
                             f"V1 {name} changed: publish a v2 contract instead of editing V1")

    def test_schema_invalid_fixtures_pin_their_reason(self):
        with self.assertRaisesRegex(ValueError, "not its schemaError"):
            self.mutated(lambda manifest, _: self.entry(manifest, "playback-unknown-state").update(
                schemaError={"keyword": "enum", "path": "/payload/sequence"}))
        with self.assertRaisesRegex(ValueError, "not its schemaError"):
            self.mutated(lambda manifest, _: self.entry(manifest, "candidates-null-id").pop("schemaError"))

    def test_http_fixture_without_component_pointer_fails(self):
        with self.assertRaisesRegex(ValueError, "components/schemas/ pointer"):
            self.mutated(lambda manifest, _: self.entry(manifest, "statistics-available").pop("pointer"))


if __name__ == "__main__":
    unittest.main()
