package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

public interface GithubCommentPublicationBatchItemMapper extends BaseMapper<GithubCommentPublicationBatchItem> {

    @Insert({
        "<script>",
        """
        insert into github_comment_publication_batch_item (
            batch_id, task_id, finding_id, file_path, line_number, target_type,
            status, success, github_comment_id, github_url, message, published_at, created_at
        ) values
        """,
        "<foreach collection='items' item='item' separator=','>",
        """
        (
            #{item.batchId}, #{item.taskId}, #{item.findingId}, #{item.filePath},
            #{item.lineNumber}, #{item.targetType}, #{item.status}, #{item.success},
            #{item.githubCommentId}, #{item.githubUrl}, #{item.message},
            #{item.publishedAt}, #{item.createdAt}
        )
        """,
        "</foreach>",
        "</script>"
    })
    int insertBatch(@Param("items") List<GithubCommentPublicationBatchItem> items);
}
