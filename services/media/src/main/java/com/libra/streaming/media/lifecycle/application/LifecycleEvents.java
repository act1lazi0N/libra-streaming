package com.libra.streaming.media.lifecycle.application;

/** What the sweeper did, for counters and logs; codes only, never keys, identifiers or remote text. */
public interface LifecycleEvents {
    void expired(int uploads);

    void deleted(int objects);

    void cleaned();

    void failed(String code);
}
