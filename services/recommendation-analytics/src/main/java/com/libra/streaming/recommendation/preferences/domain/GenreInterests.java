package com.libra.streaming.recommendation.preferences.domain;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Genre interests of one profile (RA05.1): how its accepted watched time in the interest window spreads over genres.
 *
 * <ol>
 *   <li>Each watched item counts at most one full watch ({@link InterestRules#itemCap}): rewatching adds nothing.</li>
 *   <li>Items are grouped into their movie or series; a title counts at most {@link InterestRules#titleCapMs}, so one
 *       binge cannot outweigh everything else.</li>
 *   <li>A title's weight is split equally among its distinct genres: a title tagged with five genres contributes as
 *       much in total as one tagged with a single genre.</li>
 *   <li>Watched time that cannot be attributed (unknown title, title without genres) counts towards no genre.</li>
 * </ol>
 *
 * Weights are in milliseconds; {@link #share} normalizes them to sum to 1. Genres are summed in a fixed (sorted)
 * order, so the same input always gives bit-identical values and ties stay ties.
 */
public final class GenreInterests {
    private static final GenreInterests NONE = new GenreInterests(new TreeMap<>(), 0);

    private final SortedMap<String, Double> weights;
    private final double total;

    private GenreInterests(SortedMap<String, Double> weights, double total) {
        this.weights = Collections.unmodifiableSortedMap(weights);
        this.total = total;
    }

    public static GenreInterests none() { return NONE; }

    public static GenreInterests of(List<WatchedItem> items, InterestRules rules) {
        Objects.requireNonNull(rules, "rules");
        // Title -> capped weight, and title -> its genres; sorted so the summation order never depends on input order.
        SortedMap<UUID, Long> titleWeights = new TreeMap<>();
        SortedMap<UUID, List<String>> titleGenres = new TreeMap<>();
        for (WatchedItem item : items) {
            if (item.titleId() == null || item.watchedMs() == 0) { continue; }
            long counted = Math.min(item.watchedMs(), rules.itemCap(item.durationMs()));
            titleWeights.merge(item.titleId(), counted, Long::sum);
            titleGenres.putIfAbsent(item.titleId(), List.copyOf(new LinkedHashSet<>(item.titleGenres())));
        }
        SortedMap<String, Double> weights = new TreeMap<>();
        for (var title : titleWeights.entrySet()) {
            List<String> genres = titleGenres.get(title.getKey());
            if (genres.isEmpty()) { continue; }
            double perGenre = (double) Math.min(title.getValue(), rules.titleCapMs()) / genres.size();
            for (String genre : genres) { weights.merge(genre, perGenre, Double::sum); }
        }
        if (weights.isEmpty()) { return NONE; }
        double total = 0;
        for (double weight : weights.values()) { total += weight; }
        return new GenreInterests(weights, total);
    }

    public boolean isEmpty() { return weights.isEmpty(); }

    /** Genre -> counted milliseconds, in genre order. */
    public Map<String, Double> weights() { return weights; }

    /** The genre's part of the profile's attributed watched time, in [0, 1]; 0 for a genre never watched. */
    public double share(String genre) {
        Double weight = weights.get(genre);
        return weight == null ? 0 : weight / total;
    }
}
