package com.libra.streaming.recommendation.statistics.application;

import com.libra.streaming.recommendation.statistics.domain.DayContribution;
import com.libra.streaming.recommendation.statistics.domain.DayTotals;
import com.libra.streaming.recommendation.statistics.domain.StatisticsTotals;
import com.libra.streaming.recommendation.statistics.domain.StatisticsWindow;
import java.util.List;
import java.util.UUID;

/** The daily statistics rollup. Writes join the caller's transaction; each read is one consistent statement. */
public interface StatisticsStore {
    /** Applies the contributions in the given (ascending day) order. */
    void add(UUID accountId, List<DayContribution> contributions);

    StatisticsTotals totals(StatisticsWindow window);

    /** One entry per day of the window, zeros included, in ascending order. */
    List<DayTotals> days(StatisticsWindow window);
}
