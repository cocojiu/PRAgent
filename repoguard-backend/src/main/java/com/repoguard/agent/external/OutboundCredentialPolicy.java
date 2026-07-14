package com.repoguard.agent.external;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class OutboundCredentialPolicy {

    private final OutboundEndpointPolicy endpointPolicy;

    public OutboundCredentialPolicy(OutboundEndpointPolicy endpointPolicy) {
        this.endpointPolicy = endpointPolicy;
    }

    public void requireFreshCredentialOnOriginChange(
        OutboundEndpointType type,
        String currentEndpoint,
        String requestedEndpoint,
        String submittedCredential,
        boolean credentialCurrentlyConfigured
    ) {
        if (!credentialCurrentlyConfigured || !StringUtils.hasText(currentEndpoint)
            || endpointPolicy.sameOrigin(type, currentEndpoint, requestedEndpoint)) {
            return;
        }
        if (!StringUtils.hasText(submittedCredential) || submittedCredential.trim().startsWith("****")) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "Credential must be entered again when the outbound endpoint origin changes"
            );
        }
    }
}
