package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.entity.SystemSettingsConfig;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface SystemSettingsConfigMapper extends BaseMapper<SystemSettingsConfig> {

    @Select("select * from system_settings_config where tenant_id = #{tenantId}")
    SystemSettingsConfig selectByTenantId(@Param("tenantId") long tenantId);
}
