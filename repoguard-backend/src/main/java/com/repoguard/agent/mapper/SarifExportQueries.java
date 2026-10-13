package com.repoguard.agent.mapper;

import java.util.List;
import com.repoguard.agent.mapper.ReviewFindingMapper.SarifImportBatchRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Export preflight statements inherited by the finding mapper. */
public interface SarifExportQueries {
    @Select("""
        <script>
        select id, tool_name, tool_version
        from sarif_import_batch
        where task_id = #{taskId}
          and id in
          <foreach collection="batchIds" item="batchId" open="(" separator="," close=")">
            #{batchId}
          </foreach>
        </script>
        """)
    List<SarifImportBatchRow> selectSarifExportBatches(@Param("taskId") Long taskId,
        @Param("batchIds") List<Long> batchIds);

    @Select("""
        select coalesce(sum(export_text_bytes), 0)
        from (
            select octet_length(coalesce(message, ''))
                 + octet_length(coalesce(file_path, ''))
                 + octet_length(coalesce(rule_id, '')) as export_text_bytes
            from review_finding
            where task_id = #{taskId}
              and current_attempt = 1
              and category = 'FINDING'
            order by id
            limit #{rowLimit}
        ) export_candidates
        """)
    long selectSarifExportTextBytes(@Param("taskId") Long taskId, @Param("rowLimit") int rowLimit);
}
