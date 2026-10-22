package com.libra.streaming.core.integration.media;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.function.Predicate;

final class MediaFailures {
    private MediaFailures() {}

    static boolean anyCause(Throwable start, Predicate<Throwable> test) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable cause = start;
        for (int depth = 0; cause != null && depth < 64 && seen.add(cause); depth++, cause = cause.getCause()) {
            if (test.test(cause)) { return true; }
        }
        return false;
    }
}
