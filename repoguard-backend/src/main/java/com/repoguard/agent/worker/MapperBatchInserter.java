package com.repoguard.agent.worker;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.springframework.stereotype.Component;

@Component
class MapperBatchInserter {

    static final int BATCH_SIZE = 500;

    private final SqlSessionFactory sqlSessionFactory;

    MapperBatchInserter(SqlSessionFactory sqlSessionFactory) {
        this.sqlSessionFactory = sqlSessionFactory;
    }

    /**
     * Uses a MyBatis BATCH executor and explicitly completes its session after
     * every statement has been flushed. With SpringManagedTransactionFactory
     * the commit participates in the surrounding transaction; outside one it
     * prevents a successfully flushed batch from being discarded on close.
     */
    <T> void insertAll(Class<? extends BaseMapper<T>> mapperClass, List<T> entities) {
        if (entities.isEmpty()) {
            return;
        }
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            BaseMapper<T> mapper = session.getMapper(mapperClass);
            int pending = 0;
            for (T entity : entities) {
                mapper.insert(entity);
                pending++;
                if (pending == BATCH_SIZE) {
                    session.flushStatements();
                    pending = 0;
                }
            }
            if (pending > 0) {
                session.flushStatements();
            }
            session.commit();
        }
    }
}
