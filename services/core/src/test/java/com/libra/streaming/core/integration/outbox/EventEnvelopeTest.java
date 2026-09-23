package com.libra.streaming.core.integration.outbox;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class EventEnvelopeTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void payloadIsDefensivelyCopiedOnInputAndOutput() {
        var input = mapper.createObjectNode().put("state", "initial");
        var event = new EventEnvelope(UUID.randomUUID(), "ProfileCreated", 1, UUID.randomUUID(),
                1, Instant.now(), UUID.randomUUID(), input);
        input.put("state", "changed");
        ((tools.jackson.databind.node.ObjectNode) event.payload()).put("state", "changedAgain");
        assertThat(event.payload().path("state").asString()).isEqualTo("initial");
    }

    @Test
    void invalidVersionAndPayloadAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> new EventEnvelope(UUID.randomUUID(),
                "ProfileCreated", 2, UUID.randomUUID(), 1, Instant.now(), UUID.randomUUID(),
                mapper.createObjectNode()));
        assertThatIllegalArgumentException().isThrownBy(() -> new EventEnvelope(UUID.randomUUID(),
                "ProfileCreated", 1, UUID.randomUUID(), 0, Instant.now(), UUID.randomUUID(),
                mapper.createObjectNode()));
        assertThatIllegalArgumentException().isThrownBy(() -> new EventEnvelope(UUID.randomUUID(),
                "ProfileCreated", 1, UUID.randomUUID(), 1, Instant.now(), UUID.randomUUID(),
                mapper.createArrayNode()));
    }
}
