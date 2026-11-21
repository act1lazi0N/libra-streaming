package com.libra.streaming.recommendation.ingestion.infrastructure;

import com.libra.streaming.recommendation.freshness.domain.InputTopic;
import com.libra.streaming.recommendation.ingestion.domain.CatalogChange;
import com.libra.streaming.recommendation.ingestion.domain.EventRejection;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Regression coverage for the shared ContractJson extraction. */
class JsonCoreEventDecoderTest {
    final JsonCoreEventDecoder decoder = new JsonCoreEventDecoder();

    @Test
    void preservesCatalogRevisionBindingAndMetadataDecoding() {
        var event = new IngestionFixtures().movie(2, "CatalogPublished");
        var decoded = (CatalogChange) decoder.decode(event.topic(), event.key(), event.json());
        assertThat(decoded.contentId().toString()).isEqualTo(event.key());
        assertThat(decoded.published().metadata().title()).isEqualTo(event.payload().path("published").path("metadata").path("title").stringValue());
        assertThat(decoded.active().contentId()).isEqualTo(decoded.contentId());
        assertThat(decoded.published().createdAt()).isEqualTo(IngestionFixtures.BASE);
    }

    @Test
    void stillRejectsUnknownNestedFieldsAndWrongScalarTypes() {
        var event = new IngestionFixtures().movie(2, "CatalogPublished");
        ((tools.jackson.databind.node.ObjectNode) event.payload().path("published").path("metadata")).put("unknown", true);
        assertThatThrownBy(() -> decoder.decode(event.topic(), event.key(), event.json())).isInstanceOf(EventRejection.class);
        var profile = new IngestionFixtures().profile(1, "ProfileCreated");
        profile.root().put("aggregateVersion", "1");
        assertThatThrownBy(() -> decoder.decode(profile.topic(), profile.key(), profile.json())).isInstanceOf(EventRejection.class);
    }

    @Test
    void stillRejectsDuplicateKeysWithoutLeakingTheBody() {
        var event = new IngestionFixtures().profile(1, "ProfileCreated");
        String body = event.json().replace("{", "{\"private-canary\":1,\"private-canary\":2,");
        assertThatThrownBy(() -> decoder.decode(InputTopic.PROFILES, event.key(), body)).isInstanceOf(EventRejection.class)
                .hasMessageNotContaining("private-canary");
    }
}
