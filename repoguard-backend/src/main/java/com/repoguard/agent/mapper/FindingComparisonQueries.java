package com.repoguard.agent.mapper;

import com.repoguard.agent.entity.ReviewFinding;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Attempt-scoped comparison reads and version-fenced materialization writes. */
public interface FindingComparisonQueries {
    String COMPARISON_COLUMNS = """
        id, task_id, attempt_id, finding_fingerprint, previous_finding_id,
        comparison_status, comparison_confidence, comparison_reason, comparison_version, comparison_attempt_id,
        category, severity, source, rule_id, file_path, line_number, message, recommendation, is_blocking, feedback_status,
        detector_version, rule_config_version, llm_provider, llm_model, issue_type, method_name, anchor_type
        """;

    @Select("select " + COMPARISON_COLUMNS + """
        from review_finding
        where task_id = #{taskId} and attempt_id = #{attemptId} and category = 'FINDING'
        order by id limit 5001
        """)
    List<ReviewFinding> selectAttemptForComparison(@Param("taskId") Long taskId, @Param("attemptId") Long attemptId);

    @Select("""
        <script>
        select matched.id, matched.attempt_id, matched.finding_fingerprint from (
            select finding.id, finding.attempt_id, finding.finding_fingerprint, row_number() over (
                partition by finding.finding_fingerprint order by attempt.attempt_no desc, finding.id desc
            ) as fingerprint_rank
            from review_finding finding
            join review_execution_attempt attempt on attempt.id = finding.attempt_id and attempt.task_id = finding.task_id
            where finding.task_id = #{taskId} and finding.category = 'FINDING'
              and attempt.attempt_no &lt; #{beforeAttemptNo} and attempt.status in ('COMPLETED', 'PARTIAL')
              and finding.finding_fingerprint in
              <foreach collection="fingerprints" item="fingerprint" open="(" separator="," close=")">#{fingerprint}</foreach>
        ) matched where matched.fingerprint_rank = 1
        </script>
        """)
    List<ReviewFinding> selectEarlierFingerprintMatches(@Param("taskId") Long taskId,
        @Param("beforeAttemptNo") Integer beforeAttemptNo, @Param("fingerprints") List<String> fingerprints);

    @Select("""
        select finding.id, finding.attempt_id, finding.source, finding.llm_provider, finding.rule_id,
               finding.issue_type, finding.method_name, finding.anchor_type, finding.message, finding.file_path
        from review_finding finding
        join review_execution_attempt attempt on attempt.id = finding.attempt_id and attempt.task_id = finding.task_id
        where finding.task_id = #{taskId} and finding.category = 'FINDING'
          and (finding.finding_fingerprint is null or finding.finding_fingerprint = '')
          and attempt.attempt_no < #{beforeAttemptNo} and attempt.status in ('COMPLETED', 'PARTIAL')
        order by attempt.attempt_no desc, finding.id desc limit 201
        """)
    List<ReviewFinding> selectEarlierLegacyFingerprints(@Param("taskId") Long taskId, @Param("beforeAttemptNo") Integer beforeAttemptNo);

    @Update("""
        update review_finding
        set finding_fingerprint = #{findingFingerprint},
            previous_finding_id = #{previousFindingId},
            comparison_status = #{comparisonStatus},
            comparison_confidence = #{comparisonConfidence},
            comparison_reason = #{comparisonReason},
            comparison_version = #{comparisonVersion},
            comparison_attempt_id = #{comparisonAttemptId}
        where id = #{findingId}
          and task_id = #{taskId}
          and attempt_id = #{attemptId}
          and (comparison_attempt_id = #{expectedComparisonAttemptId}
               or (comparison_attempt_id is null and #{expectedComparisonAttemptId} is null))
        """)
    int updateComparison(
        @Param("findingId") Long findingId,
        @Param("taskId") Long taskId,
        @Param("attemptId") Long attemptId,
        @Param("findingFingerprint") String findingFingerprint,
        @Param("previousFindingId") Long previousFindingId,
        @Param("comparisonStatus") String comparisonStatus,
        @Param("comparisonConfidence") java.math.BigDecimal comparisonConfidence,
        @Param("comparisonReason") String comparisonReason,
        @Param("comparisonVersion") String comparisonVersion,
        @Param("comparisonAttemptId") Long comparisonAttemptId,
        @Param("expectedComparisonAttemptId") Long expectedComparisonAttemptId
    );

}
