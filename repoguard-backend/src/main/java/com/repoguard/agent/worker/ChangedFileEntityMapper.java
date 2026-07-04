package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.github.GithubChangedFile;
import org.springframework.stereotype.Component;

@Component
class ChangedFileEntityMapper {

    ChangedFile toEntity(Long taskId, GithubChangedFile file) {
        ChangedFile changedFile = new ChangedFile();
        changedFile.setTaskId(taskId);
        changedFile.setFilePath(file.filename());
        changedFile.setChangeType(normalizeChangeType(file.status()));
        changedFile.setAdditions(normalizeLineCount(file.additions()));
        changedFile.setDeletions(normalizeLineCount(file.deletions()));
        return changedFile;
    }

    private String normalizeChangeType(String status) {
        if (status == null) {
            return "MODIFY";
        }
        return switch (status.toLowerCase()) {
            case "added" -> "ADD";
            case "removed" -> "DELETE";
            case "renamed" -> "RENAME";
            default -> "MODIFY";
        };
    }

    private Integer normalizeLineCount(Integer count) {
        return count == null ? 0 : count;
    }
}
