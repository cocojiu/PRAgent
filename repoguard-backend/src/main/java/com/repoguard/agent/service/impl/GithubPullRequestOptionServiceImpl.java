package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.dto.GithubPullRequestOption;
import com.repoguard.agent.dto.GithubPullRequestOptionsResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubPullRequestSummary;
import com.repoguard.agent.github.GithubRepositoryRef;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.service.GithubPullRequestOptionService;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Service
public class GithubPullRequestOptionServiceImpl implements GithubPullRequestOptionService {

    private final GithubPullRequestClient githubPullRequestClient;
    private final ReviewTaskMapper reviewTaskMapper;

    public GithubPullRequestOptionServiceImpl(GithubPullRequestClient githubPullRequestClient) {
        this(githubPullRequestClient, null);
    }

    @Autowired
    public GithubPullRequestOptionServiceImpl(
        GithubPullRequestClient githubPullRequestClient,
        ReviewTaskMapper reviewTaskMapper
    ) {
        this.githubPullRequestClient = githubPullRequestClient;
        this.reviewTaskMapper = reviewTaskMapper;
    }

    @Override
    public GithubPullRequestOptionsResponse listConfiguredGithubPullRequests() {
        GithubRepositoryRef repositoryRef = githubPullRequestClient.getConfiguredRepository();
        List<GithubPullRequestSummary> pullRequests = githubPullRequestClient.listOpenPullRequests();
        Set<String> existingReviewKeys = existingReviewKeys(repositoryRef, pullRequests);
        return new GithubPullRequestOptionsResponse(
            repositoryRef.owner(),
            repositoryRef.repository(),
            pullRequests.stream()
                .filter(item -> !existingReviewKeys.contains(reviewKey(item.number(), item.commit())))
                .map(item -> new GithubPullRequestOption(
                    item.number(),
                    item.title(),
                    item.branch(),
                    item.commit(),
                    item.commit(),
                    item.author(),
                    item.url(),
                    item.updatedAt()
                ))
                .toList()
        );
    }

    private Set<String> existingReviewKeys(
        GithubRepositoryRef repositoryRef,
        List<GithubPullRequestSummary> pullRequests
    ) {
        if (reviewTaskMapper == null || CollectionUtils.isEmpty(pullRequests)) {
            return Set.of();
        }
        List<Integer> prNumbers = pullRequests.stream()
            .map(GithubPullRequestSummary::number)
            .filter(number -> number != null)
            .distinct()
            .toList();
        List<String> commits = pullRequests.stream()
            .map(GithubPullRequestSummary::commit)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
        if (prNumbers.isEmpty() || commits.isEmpty()) {
            return Set.of();
        }

        List<ReviewTask> existingTasks = reviewTaskMapper.selectList(
            new LambdaQueryWrapper<ReviewTask>()
                .eq(ReviewTask::getOrganization, repositoryRef.owner())
                .eq(ReviewTask::getRepository, repositoryRef.repository())
                .in(ReviewTask::getPrNumber, prNumbers)
                .in(ReviewTask::getCommitSha, commits)
        );
        Set<String> keys = new HashSet<>();
        for (ReviewTask task : existingTasks) {
            keys.add(reviewKey(task.getPrNumber(), task.getCommitSha()));
        }
        return keys;
    }

    private String reviewKey(Integer prNumber, String commit) {
        return prNumber + "\n" + commit;
    }
}
