package com.repoguard.agent.dto;

import java.util.List;

public class FindingSeverityCountsDto {

    private Long critical;
    private Long high;
    private Long medium;
    private Long low;
    private Long info;

    public FindingSeverityCountsDto() {
    }

    public FindingSeverityCountsDto(Long critical, Long high, Long medium, Long low, Long info) {
        this.critical = critical;
        this.high = high;
        this.medium = medium;
        this.low = low;
        this.info = info;
    }

    public static FindingSeverityCountsDto empty() {
        return new FindingSeverityCountsDto(0L, 0L, 0L, 0L, 0L);
    }

    public static FindingSeverityCountsDto fromFindings(List<ReviewFindingDto> findings) {
        if (findings == null || findings.isEmpty()) {
            return empty();
        }
        FindingSeverityCountsDto counts = empty();
        for (ReviewFindingDto finding : findings) {
            counts.increment(finding.severity());
        }
        return counts;
    }

    public Long getCritical() {
        return critical;
    }

    public void setCritical(Long critical) {
        this.critical = critical;
    }

    public Long getHigh() {
        return high;
    }

    public void setHigh(Long high) {
        this.high = high;
    }

    public Long getMedium() {
        return medium;
    }

    public void setMedium(Long medium) {
        this.medium = medium;
    }

    public Long getLow() {
        return low;
    }

    public void setLow(Long low) {
        this.low = low;
    }

    public Long getInfo() {
        return info;
    }

    public void setInfo(Long info) {
        this.info = info;
    }

    public long criticalOrZero() {
        return critical == null ? 0L : critical;
    }

    public long highOrZero() {
        return high == null ? 0L : high;
    }

    public long mediumOrZero() {
        return medium == null ? 0L : medium;
    }

    public long lowOrZero() {
        return low == null ? 0L : low;
    }

    public long infoOrZero() {
        return info == null ? 0L : info;
    }

    private void increment(String severity) {
        if (severity == null) {
            info = infoOrZero() + 1;
            return;
        }
        switch (severity.trim().toLowerCase()) {
            case "critical" -> critical = criticalOrZero() + 1;
            case "high" -> high = highOrZero() + 1;
            case "medium" -> medium = mediumOrZero() + 1;
            case "low" -> low = lowOrZero() + 1;
            default -> info = infoOrZero() + 1;
        }
    }
}
