package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class LlmReviewResultParser {

    private final ObjectMapper objectMapper;
    private final LlmReviewJsonExtractor jsonExtractor;
    private final LlmReviewSchemaRepairer schemaRepairer;
    private final LlmReviewFindingMapper findingMapper;
    private final LlmReviewParseFailureSummarizer failureSummarizer;

    public LlmReviewResultParser(
        ObjectMapper objectMapper,
        LlmReviewJsonExtractor jsonExtractor,
        LlmReviewSchemaRepairer schemaRepairer,
        LlmReviewFindingMapper findingMapper,
        LlmReviewParseFailureSummarizer failureSummarizer
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.jsonExtractor = Objects.requireNonNull(jsonExtractor, "jsonExtractor");
        this.schemaRepairer = Objects.requireNonNull(schemaRepairer, "schemaRepairer");
        this.findingMapper = Objects.requireNonNull(findingMapper, "findingMapper");
        this.failureSummarizer = Objects.requireNonNull(failureSummarizer, "failureSummarizer");
    }

    public ReviewResult parse(String content) {
        try {
            JsonNode root = objectMapper.readTree(jsonExtractor.extractJsonObject(content));
            ObjectNode repairedRoot = schemaRepairer.repairAndValidateRoot(root);
            String riskLevel = repairedRoot.path("riskLevel").asText();
            return ReviewResult.completed(riskLevel, findingMapper.mapFindings(repairedRoot.path("findings")));
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse LLM review result: " + failureSummarizer.summarize(content, ex), ex);
        }
    }
}
