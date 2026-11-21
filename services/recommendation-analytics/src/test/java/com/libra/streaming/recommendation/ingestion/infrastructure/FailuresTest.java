package com.libra.streaming.recommendation.ingestion.infrastructure;

import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FailuresTest {
    @Test
    void transientSqlStatesSurviveWrapping() {
        for (String state : new String[] {"08000", "08006", "53000", "53100", "57P01", "57P02", "57P03", "40001", "40P01", "55P03"}) {
            assertThat(Failures.isTransient(new RuntimeException(new SQLException("private-marker", state))))
                    .as(state).isTrue();
        }
    }

    @Test
    void programLimitsAndUnknownFailuresAreNotTransient() {
        assertThat(Failures.isTransient(new org.springframework.dao.DataAccessResourceFailureException(
                "private-marker", new SQLException("private-marker", "54000")))).isFalse();
        assertThat(Failures.isTransient(new SQLException("private-marker", "54100"))).isFalse();
        assertThat(Failures.isTransient(new RuntimeException())).isFalse();
        assertThat(Failures.isTransient(new SQLException())).isFalse();
    }

    @Test
    void rejectionsAreDetectedThroughCauses() {
        var rejection = EventRejection.semantic("KEY_NOT_AGGREGATE");
        assertThat(Failures.rejection(new RuntimeException(new RuntimeException(rejection)))).contains(rejection);
        assertThat(Failures.rejection(new RuntimeException())).isEmpty();
    }
}

