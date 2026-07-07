package com.repoguard.agent.messaging;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.function.Consumer;

public final class RabbitPublishClaimConditions {

    private RabbitPublishClaimConditions() {
    }

    public static <T> Consumer<LambdaQueryWrapper<T>> availableLambda(
        SFunction<T, ?> claimedAtColumn,
        LocalDateTime expiredBefore
    ) {
        Objects.requireNonNull(claimedAtColumn, "claimedAtColumn");
        Objects.requireNonNull(expiredBefore, "expiredBefore");
        return wrapper -> wrapper
            .isNull(claimedAtColumn)
            .or()
            .le(claimedAtColumn, expiredBefore);
    }

    public static <T> Consumer<UpdateWrapper<T>> availableColumn(
        String claimedAtColumn,
        LocalDateTime expiredBefore
    ) {
        Objects.requireNonNull(claimedAtColumn, "claimedAtColumn");
        Objects.requireNonNull(expiredBefore, "expiredBefore");
        return wrapper -> wrapper
            .isNull(claimedAtColumn)
            .or()
            .le(claimedAtColumn, expiredBefore);
    }

    public static <T> Consumer<LambdaQueryWrapper<T>> staleQueuedLambda(
        SFunction<T, ?> claimedAtColumn,
        SFunction<T, ?> createdAtColumn,
        LocalDateTime expiredBefore
    ) {
        Objects.requireNonNull(claimedAtColumn, "claimedAtColumn");
        Objects.requireNonNull(createdAtColumn, "createdAtColumn");
        Objects.requireNonNull(expiredBefore, "expiredBefore");
        return wrapper -> wrapper
            .and(claimed -> claimed
                .isNotNull(claimedAtColumn)
                .le(claimedAtColumn, expiredBefore)
            )
            .or(unclaimed -> unclaimed
                .isNull(claimedAtColumn)
                .le(createdAtColumn, expiredBefore)
            );
    }

    public static <T> Consumer<UpdateWrapper<T>> staleQueuedColumn(
        String claimedAtColumn,
        String createdAtColumn,
        LocalDateTime expiredBefore
    ) {
        Objects.requireNonNull(claimedAtColumn, "claimedAtColumn");
        Objects.requireNonNull(createdAtColumn, "createdAtColumn");
        Objects.requireNonNull(expiredBefore, "expiredBefore");
        return wrapper -> wrapper
            .and(claimed -> claimed
                .isNotNull(claimedAtColumn)
                .le(claimedAtColumn, expiredBefore)
            )
            .or(unclaimed -> unclaimed
                .isNull(claimedAtColumn)
                .le(createdAtColumn, expiredBefore)
            );
    }
}
