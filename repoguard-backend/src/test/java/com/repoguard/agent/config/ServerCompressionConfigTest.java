package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class ServerCompressionConfigTest {

    @Test
    void applicationConfigEnablesApiResponseCompressionByDefault() {
        Properties properties = applicationProperties();

        assertThat(properties.getProperty("server.compression.enabled"))
            .isEqualTo("${SERVER_COMPRESSION_ENABLED:true}");
        assertThat(mimeTypes(properties))
            .contains(
                "application/json",
                "application/javascript",
                "text/javascript",
                "text/css",
                "text/plain"
            );
        assertThat(properties.getProperty("server.compression.min-response-size"))
            .isEqualTo("${SERVER_COMPRESSION_MIN_RESPONSE_SIZE:1024}");
    }

    private Properties applicationProperties() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        assertThat(properties).isNotNull();
        return properties;
    }

    private Iterable<String> mimeTypes(Properties properties) {
        String rawMimeTypes = properties.getProperty("server.compression.mime-types");
        assertThat(rawMimeTypes).isNotBlank();
        return Arrays.stream(rawMimeTypes.split(","))
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .toList();
    }
}
