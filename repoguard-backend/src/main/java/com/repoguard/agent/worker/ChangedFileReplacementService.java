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
    private final ChangedFileEntityMapper changedFileEntityMapper;

    ChangedFileReplacementService(ChangedFileMapper changedFileMapper, ChangedFileEntityMapper changedFileEntityMapper) {
        this.changedFileMapper = changedFileMapper;
        this.changedFileEntityMapper = changedFileEntityMapper;
    }

    void replace(Long taskId, GithubPullRequestDiff diff) {
        changedFileMapper.delete(new LambdaQueryWrapper<ChangedFile>().eq(ChangedFile::getTaskId, taskId));
        for (GithubChangedFile file : diff.files()) {
            changedFileMapper.insert(changedFileEntityMapper.toEntity(taskId, file));
        }
    }
}
