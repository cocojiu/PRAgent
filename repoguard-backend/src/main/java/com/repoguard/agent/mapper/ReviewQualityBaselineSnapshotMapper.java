package com.repoguard.agent.mapper;

import com.repoguard.agent.mapper.projection.ReviewQualityBaselineSnapshotState;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ReviewQualityBaselineSnapshotMapper {

    String SNAPSHOT_KEY = "GLOBAL";

    @Select("""
        select
            source_version as sourceVersion,
            refreshed_version as refreshedVersion,
            baseline_payload as baselinePayload,
            calculated_at as calculatedAt
        from review_quality_baseline_snapshot
        where snapshot_key = 'GLOBAL'
        """)
    ReviewQualityBaselineSnapshotState selectState();

    @Insert("""
        insert into review_quality_baseline_snapshot (
            snapshot_key,
            source_version,
            refreshed_version,
            baseline_payload,
            calculated_at
        ) values ('GLOBAL', 1, 0, null, null)
        on duplicate key update
            source_version = source_version + 1
        """)
    int markDirty();

    @Update("""
        update review_quality_baseline_snapshot
        set baseline_payload = cast(#{baselinePayload} as json),
            calculated_at = #{calculatedAt},
            refreshed_version = #{sourceVersion},
            updated_at = current_timestamp(6)
        where snapshot_key = 'GLOBAL'
          and refreshed_version <= #{sourceVersion}
        """)
    int markRefreshed(
        @Param("sourceVersion") long sourceVersion,
        @Param("baselinePayload") String baselinePayload,
        @Param("calculatedAt") LocalDateTime calculatedAt
    );
}
