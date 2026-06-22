package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.dto.ChangedFileDto;
import com.repoguard.agent.dto.MissingTestDto;
import com.repoguard.agent.dto.ReviewFindingDto;
import com.repoguard.agent.dto.ReviewTimelineItem;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ReviewTaskDetailDataLoader {

    private final ChangedFileMapper changedFileMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final ReviewTimelineQueryService timelineQueryService;
    private final ReviewTaskDetailFindingAssembler findingAssembler;

    public ReviewTaskDetailDataLoader(
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewTimelineQueryService timelineQueryService
    ) {
        this(changedFileMapper, reviewFindingMapper, timelineQueryService, new ReviewTaskDetailFindingAssembler());
    }

    public ReviewTaskDetailDataLoader(
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewTimelineQueryService timelineQueryService,
        ReviewTaskDetailFindingAssembler findingAssembler
    ) {
        this.changedFileMapper = changedFileMapper;
        this.reviewFindingMapper = reviewFindingMapper;
        this.timelineQueryService = timelineQueryService;
        this.findingAssembler = findingAssembler;
    }

    public ReviewTaskDetailData load(Long taskId) {
        List<ChangedFile> changedFiles = changedFileMapper.selectList(
            new LambdaQueryWrapper<ChangedFile>()
                .eq(ChangedFile::getTaskId, taskId)
                .orderByAsc(ChangedFile::getId)
        );

        List<ReviewFinding> findings = reviewFindingMapper.selectList(
            new LambdaQueryWrapper<ReviewFinding>()
                .eq(ReviewFinding::getTaskId, taskId)
                .orderByAsc(ReviewFinding::getId)
        );

        var timeline = timelineQueryService.loadItemsByTaskId(taskId);

        return new ReviewTaskDetailData(
            findingAssembler.toChangedFileDtos(changedFiles),
            findingAssembler.toFindingDtos(findings),
            findingAssembler.toMissingTestDtos(findings),
            timeline
        );
    }

    public record ReviewTaskDetailData(
        List<ChangedFileDto> changedFiles,
        List<ReviewFindingDto> findings,
        List<MissingTestDto> missingTests,
        List<ReviewTimelineItem> timeline
    ) {
    }
}
