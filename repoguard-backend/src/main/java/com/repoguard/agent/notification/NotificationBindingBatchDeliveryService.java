package com.repoguard.agent.notification;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.notification.query.NotificationCandidateBindingQuery;
import com.repoguard.agent.notification.delivery.NotificationDeliveryResultSummary;
import com.repoguard.agent.notification.delivery.NotificationSendResult;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
class NotificationBindingBatchDeliveryService {

    private final NotificationCandidateBindingQuery candidateBindingQuery;
    private final NotificationBindingDeliveryService bindingDeliveryService;

    NotificationBindingBatchDeliveryService(
        NotificationCandidateBindingQuery candidateBindingQuery,
        NotificationBindingDeliveryService bindingDeliveryService
    ) {
        this.candidateBindingQuery = candidateBindingQuery;
        this.bindingDeliveryService = bindingDeliveryService;
    }

    NotificationDeliveryResultSummary deliver(NotificationEvent event, NotificationMessage message) {
        List<NotificationChannelBinding> bindings = candidateBindingQuery.load(message);
        NotificationDeliveryResultSummary resultSummary = NotificationDeliveryResultSummary.empty();
        for (NotificationChannelBinding binding : bindings) {
            Optional<NotificationSendResult> deliveryResult =
                bindingDeliveryService.deliver(event, message, binding);
            if (deliveryResult.isPresent()) {
                resultSummary = resultSummary.add(deliveryResult.get());
            }
        }
        return resultSummary;
    }
}
