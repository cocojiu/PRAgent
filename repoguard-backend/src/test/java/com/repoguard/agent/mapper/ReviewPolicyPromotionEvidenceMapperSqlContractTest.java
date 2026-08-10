package com.repoguard.agent.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.ReviewPolicyPromotionEvidence;
import java.lang.reflect.Method;
import java.util.Locale;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class ReviewPolicyPromotionEvidenceMapperSqlContractTest {

    @Test
    void ruleEvidenceUsesTheSameCompleteHighRiskVersionWindowAsCalibration() throws Exception {
        String sql = selectSql(
            "selectRuleEvidence",
            String.class,
            String.class,
            long.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class
        );

        assertThat(sql)
            .contains("task.assessment_status")
            .contains("= 'complete'")
            .contains("finding.severity_norm in ('high', 'critical')")
            .contains("finding.rule_config_version = #{ruleconfigversion}")
            .contains("finding.prompt_version = #{promptversion}")
            .contains("finding.context_version = #{contextversion}")
            .contains("finding.schema_version = #{schemaversion}")
            .contains("finding.verifier_version = #{verifierversion}")
            .contains("finding.aggregation_version = #{aggregationversion}")
            .contains("sha2(concat_ws")
            .contains("bit_xor(crc32")
            .contains("samplefingerprint");
    }

    @Test
    void strategyEvidenceCapturesAllMatchingSamplesAndHighRiskGateCounts() throws Exception {
        String sql = selectSql(
            "selectStrategyEvidence",
            String.class,
            String.class,
            String.class,
            String.class,
            String.class
        );

        assertThat(sql)
            .contains("task.assessment_status")
            .contains("= 'complete'")
            .contains("upper(coalesce(finding.source, '')) like '%llm%'")
            .contains("count(*) as totalsamples")
            .contains("severity_norm in ('high', 'critical')")
            .contains("as labeledhighrisksamples")
            .contains("as confirmedvalidsamples")
            .contains("as falsepositivesamples")
            .contains("as samplecutoffat")
            .contains("as samplefingerprint");
    }

    @Test
    void mapperExposesOnlyAppendAndEvidenceCaptureOperations() throws Exception {
        Method insertMethod = ReviewPolicyPromotionEvidenceMapper.class.getMethod(
            "insert",
            ReviewPolicyPromotionEvidence.class
        );
        Insert insert = insertMethod.getAnnotation(Insert.class);

        assertThat(insert).isNotNull();
        assertThat(normalizeSql(String.join("\n", insert.value())))
            .startsWith("insert into review_policy_promotion_evidence")
            .contains("precision_wilson_lower_bound")
            .contains("sample_fingerprint");
        assertThat(ReviewPolicyPromotionEvidenceMapper.class.getDeclaredMethods())
            .extracting(Method::getName)
            .containsExactlyInAnyOrder("insert", "selectRuleEvidence", "selectStrategyEvidence");
    }

    private String selectSql(String methodName, Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = ReviewPolicyPromotionEvidenceMapper.class.getMethod(methodName, parameterTypes);
        Select select = method.getAnnotation(Select.class);
        assertThat(select).as(methodName + " @Select").isNotNull();
        return normalizeSql(String.join("\n", select.value()));
    }

    private String normalizeSql(String sql) {
        return sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
