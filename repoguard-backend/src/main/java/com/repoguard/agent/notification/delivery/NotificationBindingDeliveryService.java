package com.repoguard.agent.notification.delivery;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationDeliveryLogMapper;
import com.repoguard.agent.notification.channel.NotificationChannelAdapterRegistry;
import com.repoguard.agent.notification.NotificationMessage;
import com.repoguard.agent.notification.binding.NotificationBindingMatcher;
import com.repoguard.agent.notification.query.NotificationSuccessfulDeliveryQuery;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class NotificationBindingDeliveryService {

    private final NotificationDeliveryLogMapper deliveryLogMapper;
    private final NotificationChannelAdapterRegistry adapterRegistry;
    private final NotificationDeliveryLogFactory deliveryLogFactory;
    private final NotificationBindingMatcher bindingMatcher;
    private final NotificationSuccessfulDeliveryQuery successfulDeliveryQuery;

    public NotificationBindingDeliveryService(
        NotificationDeliveryLogMapper deliveryLogMapper,
        NotificationChannelAdapterRegistry adapterRegistry,
        NotificationDeliveryLogFactory deliveryLogFactory,
        NotificationBindingMatcher bindingMatcher,
        NotificationSuccessfulDeliveryQuery successfulDeliveryQuery
    ) {
        this.deliveryLogMapper = deliveryLogMapper;
        this.adapterRegistry = adapterRegistry;
        this.deliveryLogFactory = deliveryLogFactory;
        this.bindingMatcher = bindingMatcher;
        this.successfulDeliveryQuery = successfulDeliveryQuery;
    }

    public Optional<NotificationSendResult> deliver(
        NotificationEvent event,
        NotificationMessage message,
        NotificationChannelBinding binding
    ) {
        if (!bindingMatcher.supports(binding, event.getEventType())
            || successfulDeliveryQuery.exists(event.getId(), binding.getId())) {
            return Optional.empty();
        }
        NotificationSendResult result = adapterRegistry.get(binding.getProvider()).send(message, binding);
        deliveryLogMapper.insert(deliveryLogFactory.create(event, binding, result));
        return Optional.of(result);
    }
}
