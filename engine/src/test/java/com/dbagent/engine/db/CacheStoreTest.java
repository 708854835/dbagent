package com.dbagent.engine.db;

import com.dbagent.engine.cache.CacheSupply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缓存供给缝：Schema 缓存按系统隔离、精确匹配（忽略首尾空白）、整体替换。真实 SQLite 文件库。
 */
class CacheStoreTest {

    @TempDir
    Path tempDir;

    private CacheStore store;

    @BeforeEach
    void setUp() {
        store = new CacheStore(EngineDatabase.initialize(tempDir.resolve("data")));
    }

    @Test
    void findByCommentMatchesExactlyIgnoringWhitespace() {
        store.replaceSystem("loan-system", List.of(
                new CacheStore.Row("t_khxx", "cust_no", "varchar(64)", "  客户编号  "),
                new CacheStore.Row("t_khxx", "cust_name", "varchar(128)", "客户名称（全称）")));

        List<CacheSupply.CachedField> hits = store.findByComment("loan-system", "客户编号");
        assertEquals(1, hits.size());
        assertEquals("cust_no", hits.get(0).englishName());

        // 不做分词/相似度：部分匹配不命中
        assertTrue(store.findByComment("loan-system", "客户名称").isEmpty());
        // 首尾空白忽略
        assertEquals(1, store.findByComment("loan-system", "  客户编号 ").size());
    }

    @Test
    void cacheIsIsolatedPerSystem() {
        store.replaceSystem("loan-system", List.of(
                new CacheStore.Row("t_a", "cust_no", "varchar(64)", "客户编号")));
        store.replaceSystem("deposit-system", List.of(
                new CacheStore.Row("t_b", "cust_id", "bigint", "客户编号")));

        assertEquals("cust_no", store.findByComment("loan-system", "客户编号").get(0).englishName());
        assertEquals("cust_id", store.findByComment("deposit-system", "客户编号").get(0).englishName());
    }

    @Test
    void replaceSystemFullyReplacesOldRows() {
        store.replaceSystem("loan-system", List.of(
                new CacheStore.Row("t_a", "cust_no", "varchar(64)", "客户编号")));
        assertEquals(1, store.count("loan-system"));

        store.replaceSystem("loan-system", List.of(
                new CacheStore.Row("t_b", "org_no", "varchar(10)", "机构编号")));
        assertEquals(1, store.count("loan-system"));
        assertTrue(store.findByComment("loan-system", "客户编号").isEmpty());
        assertEquals(1, store.findByComment("loan-system", "机构编号").size());
    }
}
