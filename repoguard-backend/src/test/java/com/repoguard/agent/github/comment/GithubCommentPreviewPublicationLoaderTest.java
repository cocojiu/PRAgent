package com.repoguard.agent.github.comment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GithubCommentPreviewPublicationLoaderTest {

    private final GithubCommentPublicationMapper publicationMapper = Mockito.mock(GithubCommentPublicationMapper.class);
    private final GithubCommentPreviewPublicationLoader loader = new GithubCommentPreviewPublicationLoader(
        publicationMapper
    );

    @Test
    void loadsFindingPublicationsAndPrSummaryPublication() {
        GithubCommentPublication first = publication(1001L, "line", "first");
        GithubCommentPublication duplicate = publication(1001L, "line", "duplicate");
        GithubCommentPublication second = publication(1002L, "pull_request", "second");
        GithubCommentPublication summary = publication(null, "pull_request", "summary");
        when(publicationMapper.selectList(any())).thenReturn(List.of(first, duplicate, second));
        when(publicationMapper.selectOne(any())).thenReturn(summary);

        var result = loader.load(521L, List.of(finding(1001L), finding(1002L)));

        assertThat(result.publicationByFindingId()).hasSize(2);
        assertThat(result.publicationByFindingId().get(1001L)).isSameAs(first);
        assertThat(result.publicationByFindingId().get(1002L)).isSameAs(second);
        assertThat(result.prSummaryPublication()).isSameAs(summary);
    }

    @Test
    void skipsFindingPublicationQueryWhenNoFindings() {
        GithubCommentPublication summary = publication(null, "pull_request", "summary");
        when(publicationMapper.selectOne(any())).thenReturn(summary);

        var result = loader.load(521L, List.of());

        assertThat(result.publicationByFindingId()).isEmpty();
        assertThat(result.prSummaryPublication()).isSameAs(summary);
        verify(publicationMapper, never()).selectList(any());
    }

    private ReviewFinding finding(Long id) {
        ReviewFinding finding = new ReviewFinding();
        finding.setId(id);
        return finding;
    }

    private GithubCommentPublication publication(Long findingId, String targetType, String suffix) {
        GithubCommentPublication publication = new GithubCommentPublication();
        publication.setTaskId(521L);
        publication.setFindingId(findingId);
        publication.setTargetType(targetType);
        publication.setGithubUrl("https://github.com/comment/" + suffix);
        return publication;
    }
}
