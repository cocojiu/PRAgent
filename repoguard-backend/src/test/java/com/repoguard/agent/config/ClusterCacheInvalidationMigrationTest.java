package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.mapper.ClusterCacheInvalidationMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class ClusterCacheInvalidationMigrationTest {

    @Test
    void createsOneMonotonicCacheVersionRowPerTenant() throws IOException {
        String migration = Files.readString(
            Path.of("src/main/resources/db/migration/V72__cluster_cache_invalidation.sql"),
            StandardCharsets.UTF_8
        ).toLowerCase(Locale.ROOT);

        assertThat(migration)
            .contains("create table tenant_cache_version")
            .contains("tenant_id bigint unsigned not null")
            .contains("cache_version bigint unsigned not null default 1")
            .contains("primary key (tenant_id)")
            .contains("foreign key (tenant_id) references tenant(id) on delete cascade")
            .contains("check (cache_version > 0)")
            .contains("engine=innodb");
    }

    @Test
    void mapperAtomicallyIncrementsAndScansInTenantOrder() throws NoSuchMethodException {
        Insert increment = ClusterCacheInvalidationMapper.class
            .getMethod("increment", long.class)
            .getAnnotation(Insert.class);
        Select selectPage = ClusterCacheInvalidationMapper.class
            .getMethod("selectPage", long.class, int.class)
            .getAnnotation(Select.class);

        assertThat(String.join("\n", increment.value()).toLowerCase(Locale.ROOT))
            .contains("on duplicate key update")
            .contains("cache_version = cache_version + 1")
            .contains("updated_at = current_timestamp(6)");
        assertThat(String.join("\n", selectPage.value()).toLowerCase(Locale.ROOT))
            .contains("where tenant_id > #{aftertenantid}")
            .contains("order by tenant_id")
            .contains("limit #{limit}");
    }
}
