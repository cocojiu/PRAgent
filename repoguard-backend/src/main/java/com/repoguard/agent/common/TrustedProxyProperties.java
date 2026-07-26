package com.repoguard.agent.common;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.security.trusted-proxy")
public class TrustedProxyProperties {

    public static final List<String> DEFAULT_NETWORKS = List.of(
        "127.0.0.0/8",
        "::1/128",
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16"
    );

    private List<String> networks = new ArrayList<>(DEFAULT_NETWORKS);

    public List<String> getNetworks() {
        return networks;
    }

    public void setNetworks(List<String> networks) {
        this.networks = networks == null ? new ArrayList<>() : new ArrayList<>(networks);
    }
}
