package com.libra.streaming.recommendation.recommendations.domain;

import com.libra.streaming.recommendation.preferences.domain.GenreInterests;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

/**
 * Rule-based candidate ranking (RA05.2). Candidates are movies and series only, so a series appears once however many
 * of its episodes were watched. Titles the profile completed are left out. The rest are placed in three tiers, each
 * filling the places the previous one left:
 *
 * <ol>
 *   <li>{@link RankedTitle.Rule#GENRE_INTEREST}: titles sharing a genre with the profile's interests, by the cosine
 *       between the title's genres (each weighted 1) and the interest shares: {@code sum(share(g)) / sqrt(|genres|)}.
 *       A title matching one interest exactly outranks one diluted by many unrelated genres.</li>
 *   <li>{@link RankedTitle.Rule#POPULAR}: titles with at least one viewer in the last {@link #POPULARITY_WINDOW}, by
 *       viewers (distinct accounts with a qualified session on the title).</li>
 *   <li>{@link RankedTitle.Rule#NEWLY_PUBLISHED}: every other title, newest publication first.</li>
 * </ol>
 *
 * A new profile therefore gets popular then new titles (cold start), and a profile with little history gets its few
 * genre matches followed by the same. Every tie is broken by viewers, then newer publication (unknown last), then the
 * title ID as text (PostgreSQL's uuid order), so equal inputs always give the same list.
 */
public final class Ranking {
    public static final Duration POPULARITY_WINDOW = Duration.ofDays(7);

    private Ranking() {}

    /**
     * @param popularity viewers per title in the popularity window; an absent title has none
     * @param completed titles the profile has completed, excluded from every tier
     */
    public static List<RankedTitle> rank(Collection<TitleCandidate> candidates, GenreInterests interests,
            Map<UUID, Long> popularity, Set<UUID> completed, int limit) {
        Objects.requireNonNull(interests, "interests");
        requireLimit(limit);
        Function<TitleCandidate, Long> viewers = title -> popularity.getOrDefault(title.titleId(), 0L);
        Comparator<TitleCandidate> fallbackOrder = fallbackOrder(popularity);
        List<TitleCandidate> eligible = eligible(candidates, completed);

        List<RankedTitle> ranked = new ArrayList<>();
        Set<UUID> placed = new HashSet<>();
        if (!interests.isEmpty()) {
            record Scored(TitleCandidate title, double score) {}
            List<Scored> matches = new ArrayList<>();
            for (TitleCandidate title : eligible) {
                double score = affinity(title, interests);
                if (score > 0) { matches.add(new Scored(title, score)); }
            }
            matches.sort(Comparator.comparingDouble(Scored::score).reversed()
                    .thenComparing(Scored::title, fallbackOrder));
            take(matches.stream().map(Scored::title).toList(), RankedTitle.Rule.GENRE_INTEREST, limit, ranked, placed);
        }
        List<TitleCandidate> rest = eligible.stream().filter(title -> !placed.contains(title.titleId()))
                .sorted(fallbackOrder).toList();
        take(rest.stream().filter(title -> viewers.apply(title) > 0).toList(), RankedTitle.Rule.POPULAR, limit, ranked,
                placed);
        take(rest.stream().filter(title -> viewers.apply(title) == 0).toList(), RankedTitle.Rule.NEWLY_PUBLISHED, limit,
                ranked, placed);
        return List.copyOf(ranked);
    }

    /**
     * SIMILAR (RA05.3): titles sharing at least one genre with the seed, by the cosine between the two distinct genre
     * sets ({@code |A ∩ B| / sqrt(|A| |B|)}), then the common tie-breaks. The seed and completed titles are left out.
     * Nothing else fills the list: a seed without genres gives an empty list.
     */
    public static List<RankedTitle> similar(Collection<TitleCandidate> candidates, UUID seed,
            Collection<String> seedGenres, Map<UUID, Long> popularity, Set<UUID> completed, int limit) {
        Objects.requireNonNull(seed, "seed");
        requireLimit(limit);
        Set<String> wanted = new TreeSet<>(seedGenres);
        if (wanted.isEmpty()) { return List.of(); }
        record Scored(TitleCandidate title, double score) {}
        List<Scored> matches = new ArrayList<>();
        for (TitleCandidate title : eligible(candidates, completed)) {
            if (title.titleId().equals(seed)) { continue; }
            Set<String> genres = new TreeSet<>(title.genres());
            long shared = genres.stream().filter(wanted::contains).count();
            if (shared > 0) {
                matches.add(new Scored(title, shared / Math.sqrt((double) genres.size() * wanted.size())));
            }
        }
        matches.sort(Comparator.comparingDouble(Scored::score).reversed()
                .thenComparing(Scored::title, fallbackOrder(popularity)));
        return place(matches.stream().map(Scored::title).toList(), RankedTitle.Rule.SIMILAR_GENRES, limit);
    }

    /** POPULAR (RA05.3): titles with at least one viewer in the popularity window, by viewers, then the tie-breaks. */
    public static List<RankedTitle> popular(Collection<TitleCandidate> candidates, Map<UUID, Long> popularity,
            Set<UUID> completed, int limit) {
        requireLimit(limit);
        return place(eligible(candidates, completed).stream()
                .filter(title -> popularity.getOrDefault(title.titleId(), 0L) > 0)
                .sorted(fallbackOrder(popularity)).toList(), RankedTitle.Rule.POPULAR, limit);
    }

    /** NEW (RA05.3): every title, newest publication first (unknown last), then viewers, then the ID as text. */
    public static List<RankedTitle> newest(Collection<TitleCandidate> candidates, Map<UUID, Long> popularity,
            Set<UUID> completed, int limit) {
        requireLimit(limit);
        Comparator<TitleCandidate> order = Comparator.comparing(TitleCandidate::publishedAt,
                        Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
                .thenComparing(title -> popularity.getOrDefault(title.titleId(), 0L), Comparator.reverseOrder())
                .thenComparing(title -> title.titleId().toString());
        return place(eligible(candidates, completed).stream().sorted(order).toList(), RankedTitle.Rule.NEWLY_PUBLISHED,
                limit);
    }

    private static void requireLimit(int limit) {
        if (limit < 1) { throw new IllegalArgumentException("limit"); }
    }

    /** Viewers, then newer publication (unknown last), then the title ID as text. */
    private static Comparator<TitleCandidate> fallbackOrder(Map<UUID, Long> popularity) {
        Function<TitleCandidate, Long> viewers = title -> popularity.getOrDefault(title.titleId(), 0L);
        return Comparator.comparing(viewers, Comparator.reverseOrder())
                .thenComparing(TitleCandidate::publishedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(title -> title.titleId().toString());
    }

    /** Distinct candidates the profile has not completed, in input order. */
    private static List<TitleCandidate> eligible(Collection<TitleCandidate> candidates, Set<UUID> completed) {
        List<TitleCandidate> eligible = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (TitleCandidate candidate : candidates) {
            if (!completed.contains(candidate.titleId()) && seen.add(candidate.titleId())) { eligible.add(candidate); }
        }
        return eligible;
    }

    private static List<RankedTitle> place(List<TitleCandidate> ordered, RankedTitle.Rule rule, int limit) {
        List<RankedTitle> ranked = new ArrayList<>();
        take(ordered, rule, limit, ranked, new HashSet<>());
        return List.copyOf(ranked);
    }

    /** Cosine between the title's distinct genres and the interest shares; genres summed in sorted order. */
    static double affinity(TitleCandidate title, GenreInterests interests) {
        Set<String> genres = new TreeSet<>(title.genres());
        if (genres.isEmpty()) { return 0; }
        double sum = 0;
        for (String genre : genres) { sum += interests.share(genre); }
        return sum / Math.sqrt(genres.size());
    }

    private static void take(List<TitleCandidate> titles, RankedTitle.Rule rule, int limit, List<RankedTitle> ranked,
            Set<UUID> placed) {
        for (TitleCandidate title : titles) {
            if (ranked.size() >= limit) { return; }
            if (placed.add(title.titleId())) { ranked.add(new RankedTitle(title.titleId(), rule)); }
        }
    }

    /** The start of the popularity window ending at {@code now}. */
    public static Instant popularitySince(Instant now) { return now.minus(POPULARITY_WINDOW); }
}
