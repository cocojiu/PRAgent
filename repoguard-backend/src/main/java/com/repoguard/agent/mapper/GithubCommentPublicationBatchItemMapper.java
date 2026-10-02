package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface GithubCommentPublicationBatchItemMapper extends BaseMapper<GithubCommentPublicationBatchItem> {

    @Select({"<script>", """
        select batch_id as batchId, count(*) as itemsTotal
        from github_comment_publication_batch_item
        force index (idx_github_comment_item_tenant_task_batch_id)
        where task_id = #{taskId} and batch_id in
        """, "<foreach collection='batchIds' item='batchId' open='(' separator=',' close=')'>#{batchId}</foreach>",
        "group by batch_id", "</script>"})
    List<Map<String, Object>> selectHistoryItemCounts(@Param("taskId") Long taskId, @Param("batchIds") List<Long> batchIds);

    @Select("""
        select count(*) from github_comment_publication_batch_item
        force index (idx_github_comment_item_tenant_task_batch_id)
        where task_id = #{taskId} and batch_id = #{batchId}
        """)
    long countHistoryItems(@Param("taskId") Long taskId, @Param("batchId") Long batchId);

    @Select("""
        select id, batch_id, task_id, finding_id, file_path, line_number, target_type, status, success,
               github_comment_id, github_url, message, published_at
        from github_comment_publication_batch_item
        force index (idx_github_comment_item_tenant_task_batch_id)
        where task_id = #{taskId} and batch_id = #{batchId} and id > #{afterId}
        order by id asc limit #{limit}
        """)
    List<GithubCommentPublicationBatchItem> selectHistoryItemsAfterId(@Param("taskId") Long taskId,
        @Param("batchId") Long batchId, @Param("afterId") long afterId, @Param("limit") int limit);

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
