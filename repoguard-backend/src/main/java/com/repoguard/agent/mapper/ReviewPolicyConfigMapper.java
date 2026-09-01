package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ReviewPolicyConfigMapper extends BaseMapper<ReviewPolicyConfig> {

    @Select("select * from review_policy_config where tenant_id = #{tenantId}")
    ReviewPolicyConfig selectByTenantId(@Param("tenantId") long tenantId);
}
