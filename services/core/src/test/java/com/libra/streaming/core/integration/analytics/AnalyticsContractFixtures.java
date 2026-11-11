package com.libra.streaming.core.integration.analytics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The shared Core-Analytics V1 corpus in {@code contracts/fixtures/analytics-v1}. The Python contract gate proves each
 * template's schema expectation; Core tests prove that Core's real wire output equals the templates and that Core's
 * adapter reaches the declared outcome for every downstream body.
 */
final class AnalyticsContractFixtures {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}");

    record Fixture(JsonNode entry, Path directory) {
        String id() { return entry.path("id").asString(); }
        String text(String field) { return entry.path(field).isMissingNode() ? null : entry.path(field).asString(); }
        String template() {
            try { return Files.readString(directory.resolve(text("file"))); }
            catch (IOException exception) { throw new IllegalStateException(exception); }
        }
        /** The template with every placeholder replaced; an unmapped placeholder is a test-authoring error. */
        String instantiate(Map<String, String> values) {
            Matcher matcher = PLACEHOLDER.matcher(template());
            var result = new StringBuilder();
            while (matcher.find()) {
                String value = values.get(matcher.group(1));
                if (value == null) { throw new IllegalArgumentException("No value for {{" + matcher.group(1) + "}} in " + id()); }
                matcher.appendReplacement(result, Matcher.quoteReplacement(value));
            }
            return matcher.appendTail(result).toString();
        }
    }

    private AnalyticsContractFixtures() {}

    static List<Fixture> load(ObjectMapper mapper) {
        Path directory = null;
        for (var current = Path.of("").toAbsolutePath(); current != null && directory == null; current = current.getParent()) {
            var candidate = current.resolve("contracts/fixtures/analytics-v1");
            if (Files.isRegularFile(candidate.resolve("manifest.json"))) { directory = candidate; }
        }
        if (directory == null) { throw new IllegalStateException("contracts/fixtures/analytics-v1 not found"); }
        try {
            var fixtures = new ArrayList<Fixture>();
            for (var entry : mapper.readTree(Files.readString(directory.resolve("manifest.json"))).path("fixtures")) {
                fixtures.add(new Fixture(entry, directory));
            }
            if (fixtures.isEmpty()) { throw new IllegalStateException("Empty analytics contract manifest"); }
            return List.copyOf(fixtures);
        } catch (IOException exception) { throw new IllegalStateException(exception); }
    }

    static List<Fixture> with(List<Fixture> fixtures, String field) {
        return fixtures.stream().filter(fixture -> fixture.text(field) != null).toList();
    }

    static Fixture byField(List<Fixture> fixtures, String field, String value) {
        return fixtures.stream().filter(fixture -> value.equals(fixture.text(field))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No fixture with " + field + "=" + value));
    }
}
