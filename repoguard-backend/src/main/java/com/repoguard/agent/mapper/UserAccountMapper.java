package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.entity.UserAccount;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface UserAccountMapper extends BaseMapper<UserAccount> {

    @Select("select lock_name from user_management_guard where lock_name = 'active_admin' for update")
    String lockActiveAdminInvariant();

    @Update("""
        update user_account
        set locked_until = case
                when failed_login_count + 1 >= #{lockThreshold} then #{lockedUntil}
                else locked_until
            end,
            failed_login_count = failed_login_count + 1,
            updated_at = #{updatedAt}
        where id = #{id}
        """)
    int recordFailedLogin(
        @Param("id") Long id,
        @Param("lockThreshold") int lockThreshold,
        @Param("lockedUntil") LocalDateTime lockedUntil,
        @Param("updatedAt") LocalDateTime updatedAt
    );

    @Update("""
        update user_account
        set password_hash = #{newPasswordHash},
            session_version = coalesce(session_version, 0) + 1,
            updated_at = #{updatedAt}
        where id = #{id}
          and status = 'ACTIVE'
          and password_hash = #{currentPasswordHash}
        """)
    int updatePasswordAndRotateSession(
        @Param("id") Long id,
        @Param("currentPasswordHash") String currentPasswordHash,
        @Param("newPasswordHash") String newPasswordHash,
        @Param("updatedAt") LocalDateTime updatedAt
    );
}
