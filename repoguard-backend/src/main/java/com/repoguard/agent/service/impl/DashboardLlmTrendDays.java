package com.repoguard.agent.service.impl;

public final class DashboardLlmTrendDays {

    public static final int DEFAULT_DAYS = 7;
    public static final int THIRTY_DAYS = 30;
    public static final int NINETY_DAYS = 90;

    private DashboardLlmTrendDays() {
    }

    public static int normalize(Integer days) {
        if (days == null) {
            return DEFAULT_DAYS;
        }
        return switch (days) {
            case THIRTY_DAYS -> THIRTY_DAYS;
            case NINETY_DAYS -> NINETY_DAYS;
            default -> DEFAULT_DAYS;
        };
    }
}
