package com.repoguard.agent.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import org.apache.ibatis.annotations.Insert;
import org.junit.jupiter.api.Test;

class GithubCommentPublicationBatchItemMapperSqlContractTest {

    @Test
    void publicationHistoryUsesOneMultiValueInsertPerBoundedSlice() throws Exception {
        Method method = GithubCommentPublicationBatchItemMapper.class.getMethod("insertBatch", List.class);
        String sql = String.join(" ", method.getAnnotation(Insert.class).value())
            .replaceAll("\\s+", " ")
            .toLowerCase(Locale.ROOT);

        assertThat(sql)
            .contains("insert into github_comment_publication_batch_item")
            .contains("<foreach collection='items' item='item' separator=','>")
            .contains("#{item.batchid}")
            .contains("#{item.createdat}");
    }
}
