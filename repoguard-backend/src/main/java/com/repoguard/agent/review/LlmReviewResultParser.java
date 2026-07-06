package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class LlmReviewResultParser {

    private final ObjectMapper objectMapper;
    private final LlmReviewJsonExtractor jsonExtractor;
    private final LlmReviewSchemaRepairer schemaRepairer;
    private final LlmReviewFindingMapper findingMapper;
    private final LlmReviewParseFailureSummarizer failureSummarizer;

    public LlmReviewResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.jsonExtractor = new LlmReviewJsonExtractor();
        this.schemaRepairer = new LlmReviewSchemaRepairer(objectMapper);
        this.findingMapper = new LlmReviewFindingMapper();
        this.failureSummarizer = new LlmReviewParseFailureSummarizer();
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
