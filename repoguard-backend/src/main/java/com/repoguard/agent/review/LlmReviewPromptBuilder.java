package com.repoguard.agent.review;

import com.repoguard.agent.entity.ReviewTask;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class LlmReviewPromptBuilder {

    String buildPrompt(ReviewTask task, PullRequestDiff diff) {
        return """
            请审查下面的 GitHub PR diff，并只返回严格 JSON 对象：
            {
              "riskLevel": "INFO|LOW|MEDIUM|HIGH",
              "findings": [
                {
                  "severity": "LOW|MEDIUM|HIGH",
                  "filePath": "文件路径",
                  "lineNumber": 变更后的行号或 null,
                  "message": "问题描述",
                  "recommendation": "修复建议"
                }
              ]
            }
            PR: %s/%s#%d
            Commit SHA: %s
            标题：%s
            Diff:
            %s
            """.formatted(
                diff.owner(),
                diff.repository(),
                diff.prNumber(),
                diff.headSha(),
                task.getTitle(),
                compactDiff(diff)
            );
    }

    String promptSummary(PullRequestDiff diff) {
        int fileCount = diff.files() == null ? 0 : diff.files().size();
        int additions = 0;
        int deletions = 0;
        StringBuilder files = new StringBuilder();
        if (diff.files() != null) {
            for (int i = 0; i < diff.files().size(); i++) {
                PullRequestChangedFile file = diff.files().get(i);
                additions += file.additions() == null ? 0 : file.additions();
                deletions += file.deletions() == null ? 0 : file.deletions();
                if (i < 5) {
                    if (!files.isEmpty()) {
                        files.append(", ");
                    }
                    files.append(file.filename());
                }
            }
        }
        if (fileCount > 5) {
            files.append(", ...");
        }
        return "PR " + diff.owner() + "/" + diff.repository() + "#" + diff.prNumber()
            + "; commit=" + diff.headSha()
            + "; files=" + fileCount
            + "; additions=" + additions
            + "; deletions=" + deletions
            + "; sampleFiles=" + files;
    }

    String chunkedPromptSummary(
        PullRequestDiff diff,
        List<PullRequestDiffChunk> chunks,
        int findingCount,
        String riskLevel,
        int failedChunks
    ) {
        int additions = chunks.stream().mapToInt(chunk -> chunk.additions() == null ? 0 : chunk.additions()).sum();
        int deletions = chunks.stream().mapToInt(chunk -> chunk.deletions() == null ? 0 : chunk.deletions()).sum();
        String reasons = chunks.stream()
            .flatMap(chunk -> chunk.reasons().stream())
            .distinct()
            .limit(6)
            .reduce((first, second) -> first + "," + second)
            .orElse("standard");
        return "PR " + diff.owner() + "/" + diff.repository() + "#" + diff.prNumber()
            + "; commit=" + diff.headSha()
            + "; chunked=true"
            + "; chunks=" + chunks.size()
            + "; files=" + (diff.files() == null ? 0 : diff.files().size())
            + "; additions=" + additions
            + "; deletions=" + deletions
            + "; aggregateRisk=" + riskLevel
            + "; aggregateFindings=" + findingCount
            + "; failedChunks=" + failedChunks
            + "; chunkReasons=" + reasons;
    }

    private String compactDiff(PullRequestDiff diff) {
        StringBuilder builder = new StringBuilder();
        for (PullRequestChangedFile file : diff.files()) {
            builder.append("\n--- ").append(file.filename()).append('\n');
            if (file.patch() != null) {
                builder.append(file.patch(), 0, Math.min(file.patch().length(), 6000)).append('\n');
            }
            if (builder.length() > 20000) {
                builder.append("\n[diff truncated]\n");
                break;
            }
        }
        return builder.toString();
    }
}
