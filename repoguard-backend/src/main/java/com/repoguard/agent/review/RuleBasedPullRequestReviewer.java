package com.repoguard.agent.review;

import com.repoguard.agent.config.ReviewRuleProvider;
import com.repoguard.agent.config.ReviewRuleSettings;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RuleBasedPullRequestReviewer {

    private final ReviewRuleProvider reviewRuleProvider;

    public RuleBasedPullRequestReviewer(ReviewRuleProvider reviewRuleProvider) {
        this.reviewRuleProvider = reviewRuleProvider;
    }

    public ReviewResult review(GithubPullRequestDiff diff) {
        Map<String, ReviewRuleSettings> configuredRules = reviewRuleProvider.getRulesById();
        if (configuredRules == null) {
            configuredRules = Map.of();
        }
        List<ReviewFindingResult> findings = new ArrayList<>();
        List<GithubChangedFile> files = diff.files() == null ? List.of() : diff.files();
        for (GithubChangedFile file : files) {
            String patch = file.patch();
            if (patch == null || patch.isBlank()) {
                continue;
            }
            scanPatch(file.filename(), patch, configuredRules, findings);
        }
        scanPullRequestLevelRules(diff, configuredRules, findings);
        return ReviewResult.completed(resolveRisk(findings), findings);
    }

    private void scanPullRequestLevelRules(
        GithubPullRequestDiff diff,
        Map<String, ReviewRuleSettings> configuredRules,
        List<ReviewFindingResult> findings
    ) {
        if (diff.files() == null || diff.files().isEmpty() || hasTestChange(diff.files())) {
            return;
        }
        diff.files().stream()
            .filter(file -> isControllerApiChange(file)
                && !hasControllerSecurityFinding(file.filename(), findings)
                && isApplicable("RG-API-001", file.filename(), configuredRules))
            .findFirst()
            .ifPresent(file -> findings.add(finding(
                "MEDIUM",
                "RG-API-001",
                file.filename(),
                firstAddedLine(file.patch()),
                "Controller/API change is missing tests in the same PR",
                "Add ControllerTest, ApiContractTest, or related src/test coverage for request validation, permissions, status codes, and key response fields."
            )));
    }

    private boolean hasControllerSecurityFinding(String filePath, List<ReviewFindingResult> findings) {
        String normalizedPath = normalizePath(filePath);
        return findings.stream()
            .anyMatch(finding -> normalizedPath.equals(normalizePath(finding.filePath()))
                && "RG-AUTH-001".equals(finding.ruleId()));
    }

    private void scanPatch(
        String filePath,
        String patch,
        Map<String, ReviewRuleSettings> configuredRules,
        List<ReviewFindingResult> findings
    ) {
        String[] lines = patch.split("\\R");
        int currentLine = 0;
        for (String line : lines) {
            if (line.startsWith("@@")) {
                currentLine = parseNewFileStart(line);
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                String added = line.substring(1);
                addFindingIfMatches(filePath, currentLine, added, configuredRules, findings);
                currentLine++;
            } else if (!line.startsWith("-")) {
                currentLine++;
            }
        }
    }

    private void addFindingIfMatches(
        String filePath,
        int lineNumber,
        String line,
        Map<String, ReviewRuleSettings> configuredRules,
        List<ReviewFindingResult> findings
    ) {
        String trimmed = line.trim();
        if (isApplicable("RG-JAVA-001", filePath, configuredRules)
            && (trimmed.contains("catch (Exception") || trimmed.contains("catch(Throwable") || trimmed.contains("catch (Throwable"))) {
            findings.add(finding("MEDIUM", "RG-JAVA-001", filePath, lineNumber, "新增代码捕获了过宽的异常类型", "请捕获更具体的异常类型，并保留必要的错误上下文。"));
        }
        if (isApplicable("RG-JAVA-002", filePath, configuredRules) && trimmed.contains("System.out.print")) {
            findings.add(finding("LOW", "RG-JAVA-002", filePath, lineNumber, "新增代码使用了标准输出日志", "请改用项目日志组件，避免生产日志不可控。"));
        }
        if (isApplicable("RG-JAVA-003", filePath, configuredRules) && trimmed.contains("Thread.sleep(")) {
            findings.add(finding("MEDIUM", "RG-JAVA-003", filePath, lineNumber, "新增代码包含固定休眠", "请使用可测试的等待条件、重试策略或调度机制。"));
        }
        if (isApplicable("RG-GEN-001", filePath, configuredRules) && (trimmed.contains("TODO") || trimmed.contains("FIXME"))) {
            findings.add(finding("LOW", "RG-GEN-001", filePath, lineNumber, "新增代码包含未收敛的 TODO/FIXME", "请在合并前补充实现或明确跟踪任务。"));
        }
        if (isApplicable("RG-SECRET-001", filePath, configuredRules) && containsSensitiveLiteral(trimmed)) {
            findings.add(finding("HIGH", "RG-SECRET-001", filePath, lineNumber, "新增代码疑似硬编码敏感信息", "请改用加密配置、环境变量或密钥管理服务，并确保响应和日志只返回脱敏值。"));
        }
        if (isApplicable("RG-AUTH-001", filePath, configuredRules) && isMutatingControllerMapping(filePath, trimmed)) {
            findings.add(finding("HIGH", "RG-AUTH-001", filePath, lineNumber, "新增高危 Controller 写接口缺少显式权限门禁", "请为配置写入、评论回写、用户管理等写接口补充 @RequireRole 或等效网关权限控制。"));
        }
        if (isApplicable("RG-STATE-001", filePath, configuredRules) && writesTaskStatusString(trimmed)) {
            findings.add(finding("MEDIUM", "RG-STATE-001", filePath, lineNumber, "新增代码直接写入任务状态字符串", "请通过状态机或专门的状态应用边界完成状态流转，避免绕过准入规则和补偿语义。"));
        }
        if (isApplicable("RG-MQ-001", filePath, configuredRules) && publishesRabbitMessage(trimmed)) {
            findings.add(finding("HIGH", "RG-MQ-001", filePath, lineNumber, "新增 RabbitMQ 发布调用缺少可见补偿语义", "请确认发送失败会进入可补偿状态，并记录重试次数、下次重试时间和失败原因。"));
        }
        if (isApplicable("RG-EXT-001", filePath, configuredRules) && performsRawExternalCall(trimmed)) {
            findings.add(finding("MEDIUM", "RG-EXT-001", filePath, lineNumber, "新增外部调用缺少显式治理边界", "请通过 ExternalCallResilience 或等效封装补充超时、错误分类、限流/熔断和指标记录。"));
        }
        if (isApplicable("RG-LOG-001", filePath, configuredRules) && logsSensitiveData(trimmed)) {
            findings.add(finding("HIGH", "RG-LOG-001", filePath, lineNumber, "新增日志可能输出敏感字段", "请记录脱敏摘要或配置标识，避免 GitHub Token、LLM API Key、Webhook secret 等真实密钥进入日志。"));
        }
        if (isApplicable("RG-DB-002", filePath, configuredRules) && containsDestructiveMigration(filePath, trimmed)) {
            findings.add(finding("HIGH", "RG-DB-002", filePath, lineNumber, "新增数据库迁移包含破坏性 DDL", "请采用 expand-and-contract 兼容迁移，补充数据备份、回滚方案和灰度验证记录。"));
        }
        if (isApplicable("RG-DB-003", filePath, configuredRules) && addsRequiredColumnWithoutDefault(filePath, trimmed)) {
            findings.add(finding("HIGH", "RG-DB-003", filePath, lineNumber, "新增非空字段缺少默认值或兼容窗口", "请先添加可空字段或默认值，完成历史数据回填后再收紧非空约束。"));
        }
        if (isApplicable("RG-GH-001", filePath, configuredRules) && publishesGithubCommentDirectly(trimmed)) {
            findings.add(finding("HIGH", "RG-GH-001", filePath, lineNumber, "新增 GitHub 评论发布调用缺少显式幂等边界", "请确认发布前经过 preview/publication 检查，并在 finding 级和批次级记录回写结果。"));
        }
    }

    private boolean containsSensitiveLiteral(String trimmed) {
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!(lower.contains("password") || lower.contains("secret") || lower.contains("token") || lower.contains("apikey")
            || lower.contains("api_key") || lower.contains("accesskey") || lower.contains("access_key"))) {
            return false;
        }
        return trimmed.contains("=") && (trimmed.contains("\"") || trimmed.contains("'"));
    }

    private boolean isMutatingControllerMapping(String filePath, String trimmed) {
        String normalizedPath = normalizePath(filePath);
        if (!normalizedPath.endsWith("controller.java")) {
            return false;
        }
        return trimmed.contains("@PostMapping") || trimmed.contains("@PutMapping") || trimmed.contains("@PatchMapping")
            || trimmed.contains("@DeleteMapping");
    }

    private boolean isControllerApiChange(GithubChangedFile file) {
        if (file == null || !normalizePath(file.filename()).endsWith("controller.java")) {
            return false;
        }
        String patch = file.patch();
        if (patch == null || patch.isBlank()) {
            return false;
        }
        String lowerPatch = patch.toLowerCase(Locale.ROOT);
        return lowerPatch.contains("+@getmapping") || lowerPatch.contains("+@postmapping")
            || lowerPatch.contains("+@putmapping") || lowerPatch.contains("+@patchmapping")
            || lowerPatch.contains("+@deletemapping") || lowerPatch.contains("+@requestmapping");
    }

    private boolean hasTestChange(List<GithubChangedFile> files) {
        return files.stream()
            .map(GithubChangedFile::filename)
            .map(this::normalizePath)
            .anyMatch(path -> path.contains("/src/test/")
                || path.endsWith("controllertest.java")
                || path.endsWith("apicontracttest.java")
                || path.endsWith("integrationtest.java"));
    }

    private boolean writesTaskStatusString(String trimmed) {
        return (trimmed.contains("setStatus(\"") || trimmed.contains("setReviewStatus(\"") || trimmed.contains("setHumanReviewStatus(\""))
            && (trimmed.contains("QUEUED") || trimmed.contains("REVIEWING") || trimmed.contains("COMPLETED")
                || trimmed.contains("FAILED") || trimmed.contains("PUBLISH_FAILED") || trimmed.contains("PENDING_HUMAN_REVIEW"));
    }

    private boolean publishesRabbitMessage(String trimmed) {
        return trimmed.contains("rabbitTemplate.convertAndSend") || trimmed.contains("amqpTemplate.convertAndSend");
    }

    private boolean performsRawExternalCall(String trimmed) {
        return (trimmed.contains("restClient.") || trimmed.contains("webClient.") || trimmed.contains("RestTemplate")
            || trimmed.contains("HttpClient.newHttpClient")) && (trimmed.contains(".retrieve()") || trimmed.contains(".exchange(")
                || trimmed.contains(".send(") || trimmed.contains(".body("));
    }

    private boolean logsSensitiveData(String trimmed) {
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (!(lower.contains("log.") || lower.contains("logger."))) {
            return false;
        }
        return lower.contains("token") || lower.contains("secret") || lower.contains("password") || lower.contains("apikey")
            || lower.contains("api_key") || lower.contains("webhook");
    }

    private boolean containsDestructiveMigration(String filePath, String trimmed) {
        if (!isSqlFile(filePath)) {
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\bdrop\\s+table\\b.*") || lower.matches(".*\\bdrop\\s+column\\b.*")
            || lower.matches(".*\\btruncate\\s+table\\b.*");
    }

    private boolean addsRequiredColumnWithoutDefault(String filePath, String trimmed) {
        if (!isSqlFile(filePath)) {
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return lower.matches(".*\\badd\\s+(column\\s+)?[a-z0-9_`\".]+\\s+.*\\bnot\\s+null\\b.*")
            && !lower.contains(" default ");
    }

    private boolean publishesGithubCommentDirectly(String trimmed) {
        return trimmed.contains("publishPullRequestComments(") || trimmed.contains("publishPullRequestComment(")
            || trimmed.contains("publishLineComment(") || trimmed.contains("/pulls/{pullNumber}/comments")
            || trimmed.contains("/issues/{pullNumber}/comments");
    }

    private boolean isSqlFile(String filePath) {
        return normalizePath(filePath).endsWith(".sql");
    }

    private Integer firstAddedLine(String patch) {
        if (patch == null || patch.isBlank()) {
            return null;
        }
        int currentLine = 0;
        for (String line : patch.split("\\R")) {
            if (line.startsWith("@@")) {
                currentLine = parseNewFileStart(line);
                continue;
            }
            if (line.startsWith("+") && !line.startsWith("+++")) {
                return currentLine;
            }
            if (!line.startsWith("-")) {
                currentLine++;
            }
        }
        return null;
    }

    private boolean isApplicable(String ruleId, String filePath, Map<String, ReviewRuleSettings> configuredRules) {
        ReviewRuleSettings rule = configuredRules.get(ruleId);
        if (rule == null) {
            return true;
        }
        if (rule.disabled()) {
            return false;
        }
        if (!rule.hasFilePatterns()) {
            return true;
        }
        return List.of(rule.filePatterns().split("[,\\n]")).stream()
            .map(String::trim)
            .filter(StringUtils::hasText)
            .anyMatch(pattern -> matchesPattern(filePath, pattern));
    }

    private boolean matchesPattern(String filePath, String pattern) {
        String normalizedFilePath = normalizePath(filePath);
        String normalizedPattern = normalizePath(pattern);
        if ("*".equals(normalizedPattern)) {
            return true;
        }
        String regex = normalizedPattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".");
        return normalizedFilePath.matches(".*" + regex);
    }

    private String normalizePath(String value) {
        return value == null ? "" : value.replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    private ReviewFindingResult finding(String severity, String ruleId, String filePath, Integer lineNumber, String message, String recommendation) {
        return new ReviewFindingResult(
            severity,
            "RULE",
            ruleId,
            filePath,
            lineNumber,
            message,
            recommendation,
            confidenceFor(severity),
            "规则 " + ruleId + " 命中新增代码行 " + (lineNumber == null ? "unknown" : lineNumber),
            impactFor(ruleId),
            recommendation,
            isBlockingSeverity(severity),
            reviewDimensionFor(ruleId)
        );
    }

    private String confidenceFor(String severity) {
        if (severity == null) {
            return "LOW";
        }
        return switch (severity.toUpperCase(Locale.ROOT)) {
            case "CRITICAL", "HIGH" -> "HIGH";
            case "MEDIUM" -> "MEDIUM";
            default -> "LOW";
        };
    }

    private boolean isBlockingSeverity(String severity) {
        return severity != null && switch (severity.toUpperCase(Locale.ROOT)) {
            case "CRITICAL", "HIGH" -> true;
            default -> false;
        };
    }

    private String reviewDimensionFor(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            return "RULE";
        }
        if (ruleId.startsWith("RG-DB")) {
            return "DATABASE_COMPATIBILITY_RULE";
        }
        if (ruleId.startsWith("RG-GH")) {
            return "GITHUB_WRITEBACK_RULE";
        }
        if (ruleId.startsWith("RG-API")) {
            return "API_CONTRACT_RULE";
        }
        if (ruleId.startsWith("RG-AUTH") || ruleId.startsWith("RG-SECRET") || ruleId.startsWith("RG-LOG")) {
            return "SECURITY_RULE";
        }
        if (ruleId.startsWith("RG-MQ")) {
            return "MESSAGE_RELIABILITY_RULE";
        }
        if (ruleId.startsWith("RG-EXT")) {
            return "EXTERNAL_CALL_RULE";
        }
        return "PROJECT_RULE";
    }

    private String impactFor(String ruleId) {
        if (ruleId == null) {
            return "可能影响代码质量和长期维护成本。";
        }
        return switch (ruleId) {
            case "RG-AUTH-001" -> "高危写接口缺少门禁可能导致配置、用户或评论回写能力被越权调用。";
            case "RG-SECRET-001", "RG-LOG-001" -> "敏感信息进入代码、响应或日志后可能造成 Token 泄露和供应链访问风险。";
            case "RG-STATE-001" -> "绕过状态机可能破坏任务幂等、补偿和人工复核准入语义。";
            case "RG-MQ-001" -> "消息发布失败不可见会导致任务卡死、重复消费或无法补偿。";
            case "RG-EXT-001" -> "外部调用缺少治理可能造成请求阻塞、重试风暴或故障扩散。";
            case "RG-DB-002" -> "破坏性迁移可能导致灰度发布失败、历史数据不可恢复或回滚困难。";
            case "RG-DB-003" -> "新增非空字段缺少兼容策略可能导致历史数据升级或多版本应用启动失败。";
            case "RG-GH-001" -> "绕过回写幂等记录可能导致 GitHub PR 被重复刷评论且审计链路缺失。";
            default -> "可能影响线上稳定性、可维护性或审查结果可信度。";
        };
    }

    private int parseNewFileStart(String hunkHeader) {
        int marker = hunkHeader.indexOf('+');
        if (marker < 0) {
            return 0;
        }
        int end = hunkHeader.indexOf(' ', marker);
        String range = (end < 0 ? hunkHeader.substring(marker + 1) : hunkHeader.substring(marker + 1, end)).trim();
        int comma = range.indexOf(',');
        String start = comma < 0 ? range : range.substring(0, comma);
        try {
            return Integer.parseInt(start);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String resolveRisk(List<ReviewFindingResult> findings) {
        if (findings.stream().anyMatch(finding -> "HIGH".equals(finding.severity()))) {
            return "HIGH";
        }
        if (findings.stream().anyMatch(finding -> "MEDIUM".equals(finding.severity()))) {
            return "MEDIUM";
        }
        if (findings.stream().anyMatch(finding -> "LOW".equals(finding.severity()))) {
            return "LOW";
        }
        return "INFO";
    }
}
