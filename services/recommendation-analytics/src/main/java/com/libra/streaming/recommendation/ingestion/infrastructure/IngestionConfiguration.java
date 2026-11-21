package com.libra.streaming.recommendation.ingestion.infrastructure;

import com.libra.streaming.recommendation.catalog.application.CatalogProjection;
import com.libra.streaming.recommendation.ingestion.application.CoreEventDecoder;
import com.libra.streaming.recommendation.ingestion.application.DeadLetters;
import com.libra.streaming.recommendation.ingestion.application.DeadLetterRedrive;
import com.libra.streaming.recommendation.ingestion.application.EventIntake;
import com.libra.streaming.recommendation.ingestion.application.IntakeProbe;
import com.libra.streaming.recommendation.ingestion.application.ProcessedEvents;
import com.libra.streaming.recommendation.ingestion.application.Transactions;
import com.libra.streaming.recommendation.playback.application.PlaybackProjection;
import com.libra.streaming.recommendation.profiles.application.ProfileProjection;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class IngestionConfiguration {
    @Bean
    DeadLetters deadLetters(JdbcTemplate jdbc, Clock clock) { return new JdbcDeadLetters(jdbc, clock); }

    @Bean
    DeadLetterRedrive deadLetterRedrive(DeadLetters letters, EventIntake intake, Transactions transactions, Clock clock) {
        return new DeadLetterRedrive(letters, intake, transactions, clock, Failures::isTransient);
    }

    @Bean
    CoreEventDecoder coreEventDecoder() { return new JsonCoreEventDecoder(); }

    @Bean
    ProcessedEvents processedEvents(JdbcTemplate jdbc, Clock clock) { return new JdbcProcessedEvents(jdbc, clock); }

    @Bean
    Transactions transactions(PlatformTransactionManager manager) {
        var template = new TransactionTemplate(manager);
        return new Transactions() {
            @Override public <T> T inTransaction(java.util.function.Supplier<T> work) {
                return template.execute(status -> work.get());
            }
        };
    }

    @Bean
    EventIntake eventIntake(CoreEventDecoder decoder, ProcessedEvents receipts, Transactions transactions,
            CatalogProjection catalog, ProfileProjection profiles, PlaybackProjection playback,
            ObjectProvider<IntakeProbe> probe) {
        // Only the RA02.5 fault-injection suites register a probe.
        return new EventIntake(decoder, receipts, transactions, catalog, profiles, playback,
                probe.getIfAvailable(() -> IntakeProbe.NONE));
    }
}
