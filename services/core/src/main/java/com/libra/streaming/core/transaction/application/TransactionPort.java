package com.libra.streaming.core.transaction.application;

import java.util.function.Supplier;

/** Run a Core operation in a REQUIRED transaction, joining an existing transaction when present. */
public interface TransactionPort {
    <T> T required(Supplier<T> operation);
}
