package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class GithubCommentPreviewPublicationLoader {

    private final GithubCommentPublicationMapper githubCommentPublicationMapper;

    public GithubCommentPreviewPublicationLoader(GithubCommentPublicationMapper githubCommentPublicationMapper) {
        this.githubCommentPublicationMapper = githubCommentPublicationMapper;
    }

    public GithubCommentPreviewPublicationData load(Long taskId, List<ReviewFinding> findings) {
        return new GithubCommentPreviewPublicationData(
            loadPublicationByFindingId(taskId, findings),
            loadPrSummaryPublication(taskId)
        );
    }

    private Map<Long, GithubCommentPublication> loadPublicationByFindingId(
        Long taskId,
        List<ReviewFinding> findings
    ) {
        if (findings == null || findings.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Long> findingIds = findings.stream().map(ReviewFinding::getId).toList();
        List<GithubCommentPublication> publications = githubCommentPublicationMapper.selectList(
            new LambdaQueryWrapper<GithubCommentPublication>()
                .eq(GithubCommentPublication::getTaskId, taskId)
                .in(GithubCommentPublication::getFindingId, findingIds)
        );
        if (publications == null || publications.isEmpty()) {
            return Collections.emptyMap();
        }
        return publications.stream().collect(Collectors.toMap(
            GithubCommentPublication::getFindingId,
            Function.identity(),
            (first, ignored) -> first
        ));
    }

    private GithubCommentPublication loadPrSummaryPublication(Long taskId) {
        return githubCommentPublicationMapper.selectOne(
            new LambdaQueryWrapper<GithubCommentPublication>()
                .eq(GithubCommentPublication::getTaskId, taskId)
                .isNull(GithubCommentPublication::getFindingId)
                .eq(GithubCommentPublication::getTargetType, "pull_request")
                .last("limit 1")
        );
    }

    public record GithubCommentPreviewPublicationData(
        Map<Long, GithubCommentPublication> publicationByFindingId,
        GithubCommentPublication prSummaryPublication
    ) {
    }
}
