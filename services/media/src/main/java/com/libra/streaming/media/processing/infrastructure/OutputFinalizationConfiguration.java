package com.libra.streaming.media.processing.infrastructure;

import com.libra.streaming.media.processing.application.OutputFinalizer;
import com.libra.streaming.media.processing.application.OutputSelections;
import com.libra.streaming.media.processing.application.OutputStorage;
import com.libra.streaming.media.processing.application.SourceTranscoder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * With the transcode stage configured, the finalizer completes the pipeline and is the production job handler. It
 * runs only when the worker is separately enabled ({@code libra.media.worker.enabled}, off by default). READY rows
 * and their outbox events are committed locally; nothing publishes them to Kafka yet.
 */
@Configuration
@ConditionalOnExpression("!'${libra.media.transcode.executable:}'.isEmpty()")
class OutputFinalizationConfiguration {
    @Bean
    OutputFinalizer outputFinalizer(SourceTranscoder transcoder, OutputStorage storage, OutputSelections selections) {
        return new OutputFinalizer(transcoder, storage, selections);
    }
}
