package com.repoguard.agent.worker;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.github.GithubChangedFile;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.mapper.ChangedFileMapper;
import org.springframework.stereotype.Component;

@Component
class ChangedFileReplacementService {

    private final ChangedFileMapper changedFileMapper;

    ChangedFileReplacementService(ChangedFileMapper changedFileMapper) {
        this.changedFileMapper = changedFileMapper;
    }

    void replace(Long taskId, GithubPullRequestDiff diff) {
        changedFileMapper.delete(new LambdaQueryWrapper<ChangedFile>().eq(ChangedFile::getTaskId, taskId));
        for (GithubChangedFile file : diff.files()) {
            changedFileMapper.insert(toChangedFile(taskId, file));
        }
    }

    private ChangedFile toChangedFile(Long taskId, GithubChangedFile file) {
        ChangedFile changedFile = new ChangedFile();
        changedFile.setTaskId(taskId);
        changedFile.setFilePath(file.filename());
        changedFile.setChangeType(normalizeChangeType(file.status()));
        changedFile.setAdditions(file.additions() == null ? 0 : file.additions());
        changedFile.setDeletions(file.deletions() == null ? 0 : file.deletions());
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
}
