package com.libra.streaming.recommendation.preferences.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class InterestRulesTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 367})
    void rejectsInvalidWindow(int days) {
        assertThatIllegalArgumentException().isThrownBy(() -> new InterestRules(days, 1, 2, 3));
    }

    @Test
    void rejectsZeroOrOutOfOrderCaps() {
        assertThatIllegalArgumentException().isThrownBy(() -> new InterestRules(30, 0, 2, 3));
        assertThatIllegalArgumentException().isThrownBy(() -> new InterestRules(30, 3, 2, 4));
        assertThatIllegalArgumentException().isThrownBy(() -> new InterestRules(30, 1, 3, 2));
    }

    @Test
    void itemCapUsesUnknownDurationOrTheSmallerKnownDuration() {
        var rules = new InterestRules(30, 2, 4, 6);
        assertThat(rules.itemCap(null)).isEqualTo(2);
        assertThat(rules.itemCap(3L)).isEqualTo(3);
        assertThat(rules.itemCap(8L)).isEqualTo(4);
    }
}
