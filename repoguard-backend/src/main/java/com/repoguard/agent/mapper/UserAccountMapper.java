package com.repoguard.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.repoguard.agent.entity.UserAccount;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface UserAccountMapper extends BaseMapper<UserAccount> {

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
}
