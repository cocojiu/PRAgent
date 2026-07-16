package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

class LogbackProfileContractTest {

    private static final Path LOGBACK_CONFIG = Path.of("src/main/resources/logback-spring.xml");

    @Test
    void rollingFileIsLimitedToLocalDevelopmentAndExcludedFromProduction() throws Exception {
        Document document = parseConfig();
        Element configuration = document.getDocumentElement();

        assertThat(childAttributeValues(configuration, "conversionRule", "conversionWord"))
            .containsExactly("safeMsg", "safeEx");
        Element logPattern = directChildren(configuration, "property").stream()
            .filter(element -> "LOG_PATTERN".equals(element.getAttribute("name")))
            .findFirst()
            .orElseThrow();
        assertThat(logPattern.getAttribute("value"))
            .contains("[errorId=%X{errorId:-}]", "%safeMsg%n%safeEx")
            .doesNotContain("%msg");
        assertThat(childAttributeValues(configuration, "appender", "name"))
            .containsExactly("CONSOLE");
        assertThat(rootAppenderRefs(configuration)).containsExactly("CONSOLE");

        List<Element> profiles = directChildren(configuration, "springProfile");
        assertThat(profiles).hasSize(1);
        Element localProfile = profiles.getFirst();
        assertThat(localProfile.getAttribute("name")).isEqualTo("(dev | local) & !prod");
        assertThat(childAttributeValues(localProfile, "appender", "name"))
            .containsExactly("ROLLING_FILE");
        assertThat(rootAppenderRefs(localProfile)).containsExactly("ROLLING_FILE");
    }

    private Document parseConfig() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(LOGBACK_CONFIG.toFile());
    }

    private List<String> rootAppenderRefs(Element parent) {
        List<Element> roots = directChildren(parent, "root");
        assertThat(roots).hasSize(1);
        return childAttributeValues(roots.getFirst(), "appender-ref", "ref");
    }

    private List<String> childAttributeValues(Element parent, String tagName, String attributeName) {
        return directChildren(parent, tagName).stream()
            .map(element -> element.getAttribute(attributeName))
            .toList();
    }

    private List<Element> directChildren(Element parent, String tagName) {
        List<Element> matches = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && tagName.equals(element.getTagName())) {
                matches.add(element);
            }
        }
        return matches;
    }
}
