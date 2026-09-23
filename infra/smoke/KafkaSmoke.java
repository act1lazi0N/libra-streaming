import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

/** Host-side Compose smoke check. Creates and deletes only its own synthetic topic. */
public class KafkaSmoke {
    public static void main(String[] args) throws Exception {
        String bootstrap = "localhost:9092";
        String topic = "libra-foundation-smoke-" + UUID.randomUUID();
        try (var admin = AdminClient.create(Map.of("bootstrap.servers", bootstrap,
                "default.api.timeout.ms", 15000, "request.timeout.ms", 5000))) {
            var nodes = admin.describeCluster().nodes().get(15, TimeUnit.SECONDS);
            if (nodes.size() != 1 || nodes.stream().anyMatch(node ->
                    !node.host().equals("localhost") || node.port() != 9092)) {
                throw new IllegalStateException("Unexpected external Kafka advertised listener");
            }
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
            try {
                try (var producer = new KafkaProducer<String, String>(Map.of(
                        "bootstrap.servers", bootstrap, "acks", "all", "max.block.ms", 15000,
                        "delivery.timeout.ms", 15000, "request.timeout.ms", 5000,
                        "key.serializer", "org.apache.kafka.common.serialization.StringSerializer",
                        "value.serializer", "org.apache.kafka.common.serialization.StringSerializer"))) {
                    producer.send(new ProducerRecord<>(topic, "synthetic-key", "synthetic-value"))
                            .get(20, TimeUnit.SECONDS);
                }
                try (var consumer = new KafkaConsumer<String, String>(Map.of(
                        "bootstrap.servers", bootstrap, "group.id", topic,
                        "enable.auto.commit", false, "auto.offset.reset", "earliest",
                        "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                        "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"))) {
                    consumer.subscribe(List.of(topic));
                    long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                    while (System.nanoTime() < deadline) {
                        for (var record : consumer.poll(Duration.ofMillis(500))) {
                            if (record.key().equals("synthetic-key") && record.value().equals("synthetic-value")) {
                                System.out.println("PASS: Kafka host metadata and acknowledged produce/consume");
                                return;
                            }
                        }
                    }
                    throw new IllegalStateException("Kafka smoke record was not received");
                }
            } finally {
                admin.deleteTopics(List.of(topic)).all().get(15, TimeUnit.SECONDS);
            }
        }
    }
}
