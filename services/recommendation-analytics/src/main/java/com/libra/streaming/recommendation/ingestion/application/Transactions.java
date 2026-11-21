package com.libra.streaming.recommendation.ingestion.application;

import java.util.function.Supplier;

/** Runs work in one database transaction that commits before the method returns. */
public interface Transactions {
    <T> T inTransaction(Supplier<T> work);
}
