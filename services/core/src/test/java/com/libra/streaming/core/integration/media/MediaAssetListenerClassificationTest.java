package com.libra.streaming.core.integration.media;

import com.libra.streaming.core.catalog.MediaProjectionService;
import com.libra.streaming.core.events.infrastructure.EventEnvelope;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.support.SQLStateSQLExceptionTranslator;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;

class MediaAssetListenerClassificationTest {
    private final MediaProjectionService projections = mock(MediaProjectionService.class);
    private final MediaEventDecoder decoder = mock(MediaEventDecoder.class);
    private final EventEnvelope event = mock(EventEnvelope.class);
    private final ConsumerRecord<String, String> record = new ConsumerRecord<>("media.assets.v1", 0, 0L, "asset", "private parser input");
    private final MediaAssetListener listener = new MediaAssetListener(projections, decoder);

    @BeforeEach void decode() { when(decoder.decode(record.key(), record.value())).thenReturn(event); }

    static Stream<RuntimeException> transientFailures() {
        return Stream.of(
                new CannotGetJdbcConnectionException("private connection detail", new SQLTransientConnectionException("private SQL")),
                new TransientDataAccessException("private SQL") {},
                new CannotCreateTransactionException("private transaction detail"),
                new RecoverableDataAccessException("private SQL"),
                new DataAccessResourceFailureException("private SQL"),
                new IllegalStateException("private wrapper", new SQLTransientException("private SQL")),
                new IllegalStateException("private wrapper", new SQLRecoverableException("private SQL")),
                new QueryTimeoutException("private SQL"),
                new CannotAcquireLockException("private SQL"),
                new PessimisticLockingFailureException("private SQL"),
                new IllegalStateException("private wrapper", new CannotCreateTransactionException("private transaction detail")));
    }

    @ParameterizedTest @MethodSource("transientFailures")
    void transientProjectionFailuresAreRedacted(RuntimeException failure) {
        assertTransient(failure);
    }

    static Stream<RuntimeException> poisonFailures() {
        return Stream.of(new DataIntegrityViolationException("private SQL"),
                new BadSqlGrammarException("private task", "private SQL", new SQLException("private SQL")),
                new IllegalStateException("private detail"),
                new IllegalStateException("private wrapper", new DataIntegrityViolationException("private SQL")));
    }

    @ParameterizedTest @MethodSource("poisonFailures")
    void permanentProjectionFailuresAreRedacted(RuntimeException failure) {
        doThrow(failure).when(projections).accept(record.key(), event);
        assertPoison();
    }

    @ParameterizedTest @ValueSource(strings = {"08006", "08001", "57P01", "53300", "40001", "40P01", "55P03"})
    void transientSqlStatesAreRetried(String state) {
        assertTransient(new IllegalStateException("private wrapper", new SQLException("private SQL", state)));
    }

    @ParameterizedTest @ValueSource(strings = {"54000", "23505", "42P01", "22001", ""})
    void permanentSqlStatesArePoison(String state) {
        doThrow(new IllegalStateException("private wrapper", new SQLException("private SQL", state)))
                .when(projections).accept(record.key(), event);
        assertPoison();
    }

    @Test void nullSqlStateIsPoison() {
        doThrow(new IllegalStateException("private wrapper", new SQLException("private SQL")))
                .when(projections).accept(record.key(), event);
        assertPoison();
    }

    @Test void programLimitOverridesResourceFailureWrapper() {
        doThrow(new DataAccessResourceFailureException("private SQL", new SQLException("private SQL", "54000")))
                .when(projections).accept(record.key(), event);
        assertPoison();
    }

    @Test void programLimitOverridesTransientSqlSubclass() {
        doThrow(new IllegalStateException("private wrapper", new SQLTransientException("private SQL", "54000")))
                .when(projections).accept(record.key(), event);
        assertPoison();
    }

    @Test void programLimitOverridesAnEarlierTransientSqlState() {
        var failure = new SQLException("private connection", "08006", new SQLException("private SQL", "54000"));
        doThrow(new TransactionSystemException("private transaction", failure)).when(projections).accept(record.key(), event);
        assertPoison();
    }

    @Test void transactionSystemExceptionPreservesTransientSqlStateClassification() {
        assertTransient(new TransactionSystemException("private transaction", new SQLException("private SQL", "08006")));
    }

    static Stream<Arguments> sqlStateTranslations() {
        return Stream.of(
                Arguments.of("08006", DataAccessResourceFailureException.class, true),
                Arguments.of("53300", DataAccessResourceFailureException.class, true),
                Arguments.of("57P01", DataAccessResourceFailureException.class, true),
                Arguments.of("40001", CannotAcquireLockException.class, true),
                Arguments.of("40P01", PessimisticLockingFailureException.class, true),
                Arguments.of("54000", DataAccessResourceFailureException.class, false),
                Arguments.of("23505", DuplicateKeyException.class, false),
                Arguments.of("42P01", BadSqlGrammarException.class, false),
                Arguments.of("22001", DataIntegrityViolationException.class, false));
    }

    @ParameterizedTest @MethodSource("sqlStateTranslations")
    void springSqlStateTranslationsFollowTheSameRules(String state, Class<? extends RuntimeException> type, boolean retryable) {
        var translated = new SQLStateSQLExceptionTranslator().translate("task", "sql", new SQLException("private SQL", state));
        assertThat(translated).isExactlyInstanceOf(type);
        if (retryable) { assertTransient(translated); }
        else {
            doThrow(translated).when(projections).accept(record.key(), event);
            assertPoison();
        }
    }

    @Test void decoderFailuresAreRedacted() {
        when(decoder.decode(record.key(), record.value())).thenThrow(new IllegalArgumentException("private parser input"));
        assertPoison();
        verifyNoInteractions(projections);
    }

    @Test void causeCyclesTerminate() {
        var first = new IllegalStateException("private first");
        var second = new IllegalStateException("private second");
        first.initCause(second); second.initCause(first);
        doThrow(first).when(projections).accept(record.key(), event);
        assertTimeoutPreemptively(Duration.ofSeconds(2), this::assertPoison);
        assertBoundedBackOff(first);
    }

    @Test void causeTraversalHasADepthLimit() {
        RuntimeException failure = new RecoverableDataAccessException("private SQL");
        for (int i = 0; i < 100; i++) { failure = new IllegalStateException("private wrapper", failure); }
        doThrow(failure).when(projections).accept(record.key(), event);
        assertPoison();
        Exception wrapped = new MediaEventRetryableException();
        for (int i = 0; i < 100; i++) { wrapped = new IllegalStateException("private wrapper", wrapped); }
        assertBoundedBackOff(wrapped);
    }

    @Test void retryableBackOffRemainsUnboundedAndCapped() {
        assertRetryableBackOff(new MediaEventRetryableException());
    }

    @Test void listenerExecutionWrapperSelectsRetryableBackOff() {
        assertRetryableBackOff(new ListenerExecutionFailedException("listener failed", new MediaEventRetryableException()));
    }

    @Test void poisonAndPlainExceptionsKeepTwoRetries() {
        assertBoundedBackOff(new DataIntegrityViolationException("private SQL"));
        assertBoundedBackOff(new IllegalStateException("Media event processing failed"));
        assertBoundedBackOff(new Exception("plain failure"));
        assertBoundedBackOff(new ListenerExecutionFailedException("listener failed", new IllegalStateException("Media event processing failed")));
    }

    @Test void retryMetricsExposeOnlyTheFailureKind() {
        var metrics = new SimpleMeterRegistry();
        try {
            var retries = MediaConsumerConfiguration.retryListener(metrics);
            retries.failedDelivery(record, new ListenerExecutionFailedException("listener failed", new MediaEventRetryableException()), 1);
            retries.failedDelivery(record, new IllegalStateException("Media event processing failed"), 1);
            var transientCount = metrics.get("libra.integration.media.retries").tag("kind", "transient").counter();
            var poisonCount = metrics.get("libra.integration.media.retries").tag("kind", "poison").counter();
            assertThat(transientCount.count()).isEqualTo(1.0);
            assertThat(poisonCount.count()).isEqualTo(1.0);
            assertThat(transientCount.getId().getTags()).containsExactly(Tag.of("kind", "transient"));
            assertThat(poisonCount.getId().getTags()).containsExactly(Tag.of("kind", "poison"));
            assertThat(metrics.getMeters()).hasSize(2);
        } finally { metrics.close(); }
    }

    private void assertTransient(RuntimeException failure) {
        doThrow(failure).when(projections).accept(record.key(), event);
        assertThatThrownBy(() -> listener.receive(record)).isExactlyInstanceOf(MediaEventRetryableException.class)
                .hasMessage("Media event processing temporarily unavailable").hasNoCause()
                .satisfies(exception -> {
                    assertThat(exception.getStackTrace()).isEmpty();
                    assertThat(exception.getSuppressed()).isEmpty();
                });
    }

    private void assertPoison() {
        assertThatThrownBy(() -> listener.receive(record)).isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("Media event processing failed").hasNoCause();
    }

    private void assertRetryableBackOff(Exception exception) {
        var backOff = MediaConsumerConfiguration.backOffFor(exception);
        assertThat(backOff).isInstanceOf(ExponentialBackOff.class);
        assertThat(((ExponentialBackOff) backOff).getMaxElapsedTime()).isEqualTo(Long.MAX_VALUE);
        var execution = backOff.start();
        for (int i = 0; i < 100; i++) {
            assertThat(execution.nextBackOff()).isEqualTo(i < 5 ? 1000L << i : 30000L);
        }
    }

    private void assertBoundedBackOff(Exception exception) {
        var execution = MediaConsumerConfiguration.backOffFor(exception).start();
        assertThat(execution.nextBackOff()).isEqualTo(1000L);
        assertThat(execution.nextBackOff()).isEqualTo(1000L);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }
}
