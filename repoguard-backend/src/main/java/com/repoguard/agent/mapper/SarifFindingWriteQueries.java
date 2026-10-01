package com.repoguard.agent.mapper;

import com.repoguard.agent.entity.ReviewFinding;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Bounded multi-row writes inherited by the finding mapper; tenant comes from server context. */
public interface SarifFindingWriteQueries {
    @Select("select @@max_allowed_packet")
    long selectSarifWritePacketBytes();

    @Insert("""
        <script>
        insert into review_finding (
            tenant_id, task_id, attempt_id, source_batch_id, current_attempt, category, severity, source,
            rule_id, file_path, line_number, message, recommendation, confidence, evidence, impact,
            fix_example, is_blocking, enforcement_mode, policy_reason, issue_type, related_files,
            blocking_candidate, verification_status, detector_version, rule_config_version,
            prompt_version, context_version, schema_version, verifier_version, aggregation_version,
            policy_version, original_severity, original_confidence, original_is_blocking,
            downgrade_reason, block_reason, anchor_type, review_dimension, finding_fingerprint,
            comparison_status, comparison_confidence, comparison_reason, comparison_version
        ) values
        <foreach collection="findings" item="finding" separator=",">
            (#{tenantId}, #{finding.taskId}, #{finding.attemptId}, #{finding.sourceBatchId}, 1,
             'FINDING', #{finding.severity}, 'SARIF', #{finding.ruleId}, #{finding.filePath},
             #{finding.lineNumber}, #{finding.message}, #{finding.recommendation}, 'HIGH',
             #{finding.evidence}, 'third_party_scan', #{finding.fixExample}, 0, 'COMMENT',
             'sarif_import', #{finding.issueType}, '', 0, 'NOT_REQUIRED', 'sarif-2.1.0', 1,
             'not-applicable', 'not-applicable', 'sarif-2.1.0', 'not-applicable', 'sarif-import-v1',
             1, #{finding.originalSeverity}, 'HIGH', 0, '', '', 'ADDED_LINE', 'SECURITY',
             #{finding.findingFingerprint}, 'UNMATCHED', 0, 'PENDING_COMPARISON', #{finding.comparisonVersion})
        </foreach>
        </script>
        """)
    int insertSarifFindings(@Param("tenantId") long tenantId, @Param("findings") List<ReviewFinding> findings);
}
