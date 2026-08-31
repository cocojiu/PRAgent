package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.entity.ChangedFile;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface ChangedFileMapper extends BaseMapper<ChangedFile> {

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
        select file.*
        from changed_file file
        where file.task_id = #{taskId}
          and file.current_attempt = 1
          and exists (
              select 1
              from review_finding finding
              where finding.task_id = file.task_id
                and finding.current_attempt = 1
                and finding.category = 'FINDING'
                and finding.file_path = file.file_path
          )
        order by file.id asc
        </script>
        """)
    Page<ChangedFile> selectChangedFilesWithFindings(Page<ChangedFile> page, @Param("taskId") Long taskId);

    @Select("""
        <script>
        select file.*
        from changed_file file
        where file.task_id = #{taskId}
          and file.current_attempt = 1
          and not exists (
              select 1
              from review_finding finding
              where finding.task_id = file.task_id
                and finding.current_attempt = 1
                and finding.category = 'FINDING'
                and finding.file_path = file.file_path
          )
        order by file.id asc
        </script>
        """)
    Page<ChangedFile> selectChangedFilesWithoutFindings(Page<ChangedFile> page, @Param("taskId") Long taskId);
}
