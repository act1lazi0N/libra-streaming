package com.libra.streaming.recommendation.convergence;

import java.util.*;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;

/** PostgreSQL renders all columns in UTC, including jsonb, arrays, nulls and timestamps. */
record ProjectionDump(List<String> lines) {
    static ProjectionDump read(JdbcTemplate jdbc) {
        return jdbc.execute((ConnectionCallback<ProjectionDump>) connection -> {
            var lines = new ArrayList<String>();
            try (var statement = connection.createStatement()) {
                statement.execute("SET TIME ZONE 'UTC'");
                statement.execute("SET datestyle = 'ISO, YMD'");
                for (String table : List.of("catalog_nodes", "profiles", "playback_facts", "playback_sessions", "profile_watch_days", "statistics_account_days", "processed_events")) {
                    String key = switch (table) {
                        case "catalog_nodes" -> "content_id";
                        case "profile_watch_days" -> "profile_id, content_id, day";
                        case "profiles" -> "profile_id";
                        case "playback_facts" -> "session_id, sequence";
                        case "playback_sessions" -> "session_id";
                        case "statistics_account_days" -> "day, account_id";
                        default -> "event_id";
                    };
                    // Include receipt identity as well; only its nondeterministic processing time is omitted.
                    String value = table.equals("processed_events") ? "(to_jsonb(t) - 'processed_at')::text" : "to_jsonb(t)::text";
                    try (var rows = statement.executeQuery("SELECT " + value + " FROM " + table + " t ORDER BY " + key)) {
                        while (rows.next()) { lines.add(table + " " + rows.getString(1)); }
                    }
                }
            }
            return new ProjectionDump(List.copyOf(lines));
        });
    }

    String diff(ProjectionDump actual) {
        var missing = new ArrayList<>(lines); missing.removeAll(actual.lines);
        var extra = new ArrayList<>(actual.lines); extra.removeAll(lines);
        return "Missing (-):\n- " + String.join("\n- ", missing) + "\nUnexpected (+):\n+ " + String.join("\n+ ", extra);
    }

    void assertMatches(ProjectionDump actual, String description) {
        assertThat(actual.equals(this)).as(description + "\n" + diff(actual)).isTrue();
    }

    static void assertTotals(JdbcTemplate jdbc, CanonicalEvents canonical) {
        canonical.accepted.forEach((session, sum) -> {
            var row = jdbc.queryForMap("SELECT accepted_watched_ms, qualified_sequence FROM playback_sessions WHERE session_id = ?", session);
            assertThat(((Number) row.get("accepted_watched_ms")).longValue()).as("canonical session increment sum").isEqualTo(sum);
            assertThat(((Number) row.get("qualified_sequence")).longValue()).as("first qualifying sequence")
                    .isEqualTo(canonical.qualifying.get(session));
        });
    }
}
