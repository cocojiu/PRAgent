package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.entity.ChangedFile;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ChangedFileMapper extends BaseMapper<ChangedFile> {

    @Select("""
        <script>
        select cf.id as changedFileId, count(finding.id) as total
        from changed_file cf
        left join review_finding finding
          on finding.task_id = cf.task_id
         and finding.current_attempt = 1
         and finding.category = 'FINDING'
         and cast(finding.file_path as binary) = cast(cf.file_path as binary)
        where cf.task_id = #{taskId}
          and cf.current_attempt = 1
          and cf.id in
          <foreach collection="fileIds" item="fileId" open="(" separator="," close=")">
            #{fileId}
          </foreach>
        group by cf.id
        </script>
        """)
    List<FileFindingCount> selectFindingCountsForFiles(@Param("taskId") Long taskId,
        @Param("fileIds") List<Long> fileIds);

    record FileFindingCount(Long changedFileId, Long total) {
    }

    @Update("""
        update changed_file
        set current_attempt = 0
        where task_id = #{taskId}
          and current_attempt = 1
        """)
    int markCurrentAttemptHistorical(Long taskId);

    @Select("""
        select *
        from changed_file
        where task_id = #{taskId}
          and current_attempt = 1
        order by (coalesce(additions, 0) + coalesce(deletions, 0)) desc, id asc
        limit #{limit}
        """)
    List<ChangedFile> selectTopChangedFilesByChurn(@Param("taskId") Long taskId, @Param("limit") int limit);

    @Select("""
        <script>
        select cf.*
        from changed_file cf
        where cf.task_id = #{taskId}
          and cf.current_attempt = 1
          and exists (
              select 1
              from review_finding finding
              where finding.task_id = cf.task_id
                and finding.current_attempt = 1
                and finding.category = 'FINDING'
                and cast(finding.file_path as binary) = cast(cf.file_path as binary)
          )
        order by cf.id asc
        </script>
        """)
    Page<ChangedFile> selectChangedFilesWithFindings(Page<ChangedFile> page, @Param("taskId") Long taskId);

    @Select("""
        <script>
        select cf.*
        from changed_file cf
        where cf.task_id = #{taskId}
          and cf.current_attempt = 1
          and not exists (
              select 1
              from review_finding finding
              where finding.task_id = cf.task_id
                and finding.current_attempt = 1
                and finding.category = 'FINDING'
                and cast(finding.file_path as binary) = cast(cf.file_path as binary)
          )
        order by cf.id asc
        </script>
        """)
    Page<ChangedFile> selectChangedFilesWithoutFindings(Page<ChangedFile> page, @Param("taskId") Long taskId);
}
