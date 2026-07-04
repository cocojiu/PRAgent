package com.repoguard.agent.dashboard;

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

    public static DashboardReviewTrendWindow forTest(Clock clock) {
        return new DashboardReviewTrendWindow(clock);
    }

    public LocalDate startDate() {
        return LocalDate.now(clock).minusDays(DEFAULT_DAYS - 1L);
    }

    public int days() {
        return DEFAULT_DAYS;
    }
}
