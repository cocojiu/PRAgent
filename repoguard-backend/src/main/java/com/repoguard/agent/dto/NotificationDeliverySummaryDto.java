package com.repoguard.agent.dto;

import java.util.List;

public record NotificationDeliverySummaryDto(
    List<String> providers,
    Integer deliveryCount,
    Integer failedDeliveryCount,
    String latestDeliveryStatus
) {
}
