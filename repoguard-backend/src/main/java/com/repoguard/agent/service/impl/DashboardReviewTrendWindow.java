package com.repoguard.agent.service.impl;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class DashboardReviewTrendWindow {

    private static final int DEFAULT_DAYS = 7;

    private final Clock clock;

    @Autowired
    DashboardReviewTrendWindow() {
        this(Clock.systemDefaultZone());
    }

    private DashboardReviewTrendWindow(Clock clock) {
        this.clock = clock;
    }

    static DashboardReviewTrendWindow forTest(Clock clock) {
        return new DashboardReviewTrendWindow(clock);
    }

    LocalDate startDate() {
        return LocalDate.now(clock).minusDays(DEFAULT_DAYS - 1L);
    }

    LocalDate startDate(LocalDate latestReviewDate) {
        LocalDate currentStartDate = startDate();
        if (latestReviewDate == null || !latestReviewDate.isBefore(currentStartDate)) {
            return currentStartDate;
        }
        return latestReviewDate.minusDays(DEFAULT_DAYS - 1L);
    }

    int days() {
        return DEFAULT_DAYS;
    }
}
