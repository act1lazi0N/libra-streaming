package com.libra.streaming.media.processing.application;

import com.libra.streaming.media.processing.domain.JobLease;
import com.libra.streaming.media.processing.domain.ProcessingFailure;
import java.time.Duration;
import java.util.Optional;

/** Each operation owns a short transaction. No processing runs inside that transaction. */
public interface JobLeases {
    Optional<JobLease> claim(Duration lifetime);
    boolean renew(JobLease lease, Duration lifetime);
    boolean retry(JobLease lease, ProcessingFailure failure, Duration delay);
    boolean fail(JobLease lease, ProcessingFailure failure);
    boolean release(JobLease lease);
}
