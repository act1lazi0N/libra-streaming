package com.libra.streaming.recommendation.recommendations.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The recommendation lists of analytics-internal.v2 (RA05.3), each with the only rules that may place a title in it.
 * A reason Analytics reports is always the rule that actually selected the title.
 */
public enum ListKind {
    /** The RA05.2 ranking: genre interests, then popular, then newly published. */
    PERSONAL(EnumSet.of(RankedTitle.Rule.GENRE_INTEREST, RankedTitle.Rule.POPULAR, RankedTitle.Rule.NEWLY_PUBLISHED)),
    /** Titles sharing genres with a seed title; never filled with unrelated titles. */
    SIMILAR(EnumSet.of(RankedTitle.Rule.SIMILAR_GENRES)),
    /** Titles watched by accounts in the last 7 days; never filled with unwatched titles. */
    POPULAR(EnumSet.of(RankedTitle.Rule.POPULAR)),
    /** Every title, newest publication first. */
    NEW(EnumSet.of(RankedTitle.Rule.NEWLY_PUBLISHED));

    /** Identifies the rules below in every V2 answer; changes whenever a rule or an order changes. */
    public static final String ALGORITHM_VERSION = "rules-v1";

    private final Set<RankedTitle.Rule> rules;

    ListKind(Set<RankedTitle.Rule> rules) { this.rules = Set.copyOf(rules); }

    public Set<RankedTitle.Rule> rules() { return rules; }
}
