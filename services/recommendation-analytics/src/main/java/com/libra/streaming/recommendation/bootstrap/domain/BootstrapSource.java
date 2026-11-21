package com.libra.streaming.recommendation.bootstrap.domain;

/**
 * What a bootstrap reads from Core, in the order it reads them. Profiles come first so that tombstones exist before
 * replayed playback facts arrive (the facts would be unlinked either way; this keeps the run's work minimal).
 */
public enum BootstrapSource { PROFILES, CATALOG, PLAYBACK }
