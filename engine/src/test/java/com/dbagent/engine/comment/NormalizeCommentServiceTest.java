package com.dbagent.engine.comment;

import com.dbagent.engine.db.CacheStore;
import com.dbagent.engine.db.EngineDatabase;
import com.dbagent.engine.db.NormalizedCommentStore;
import com.dbagent.engine.llm.LlmAssistService;
import com.dbagent.engine.llm.LlmNamingProvider;
import com.dbagent.engine.rules.LlmRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线 COMMENT 规范化编排：确定性清洗优先（不触 LLM）、LLM 兜底转标准 JSON、
 * 同键同原文跳过（重跑不重复调 LLM）、Schema 缓存刷新后按键回填不重跑。
 */
class NormalizeCommentServiceTest {

    private static final String SYSTEM = "CORE";

    @TempDir
    Path tempDir;

    private Path db;
    private CacheStore cacheStore;
    private NormalizedCommentStore ncStore;

    @BeforeEach
    void setUp() {
        db = EngineDatabase.initialize(tempDir);
        cacheStore = new CacheStore(db);
        ncStore = new NormalizedCommentStore(db);
    }

    /** 计数 LLM 桩：仅记录 normalizeComment 调用并返回预置结果。 */
    private static LlmAssistService llmWith(Map<String, String> results,
                                            AtomicInteger callCount) {
        LlmNamingProvider provider = new LlmNamingProvider() {
            @Override
            public List<Candidate> suggest(FieldContext context) {
                return List.of();
            }

            @Override
            public String normalizeComment(String rawComment) {
                callCount.incrementAndGet();
                return results.get(rawComment);
            }
        };
        LlmRules rules = new LlmRules();
        rules.enabled = true;
        return new LlmAssistService(provider, rules);
    }

    private void seed(CacheStore.Row... rows) {
        cacheStore.replaceSystem(SYSTEM, List.of(rows));
    }

    @Test
    void deterministicCleaningIsPreferredAndNeverCallsLlm() {
        AtomicInteger llmCalls = new AtomicInteger();
        seed(new CacheStore.Row("t_a", "cust_no", "varchar(64)", "客户　编号"),
                new CacheStore.Row("t_a", "gender", "char(2)", "性别：01-男，02-女"),
                new CacheStore.Row("t_a", "remark", "varchar(128)", ""));

        NormalizeCommentService.Report report;
        try (LlmAssistService llm = llmWith(Map.of(), llmCalls)) {
            report = new NormalizeCommentService(cacheStore, ncStore, llm)
                    .normalize(SYSTEM, 20, 0);
        }
        assertEquals(2, report.total());
        assertEquals(2, report.deterministic());
        assertEquals(0, report.llmNormalized());
        assertEquals(0, report.failed());
        assertEquals(0, report.skipped());
        assertEquals(2, report.backfilled());
        assertEquals(0, llmCalls.get(), "确定性可解析时不得调用 LLM");
        // 回填后缓存 COMMENT 为规范化名（含代码清单的只留名称）
        assertEquals(1, cacheStore.findByComment(SYSTEM, "客户编号").size());
        assertEquals(1, cacheStore.findByComment(SYSTEM, "性别").size());
    }

    @Test
    void llmFallbackOnlyForDeterministicFailuresAndJsonIsValidated() {
        AtomicInteger llmCalls = new AtomicInteger();
        seed(new CacheStore.Row("t_a", "quota_status", "char(2)", "额度状态？？详见附件【2023版】"),
                new CacheStore.Row("t_a", "txn_type", "char(2)", "交易类型？？未知"));

        Map<String, String> results = Map.of(
                "额度状态？？详见附件【2023版】", "{\"name\":\"额度状态\"}",
                "交易类型？？未知", "not-json");
        NormalizeCommentService.Report report;
        try (LlmAssistService llm = llmWith(results, llmCalls)) {
            report = new NormalizeCommentService(cacheStore, ncStore, llm)
                    .normalize(SYSTEM, 20, 0);
        }
        assertEquals(1, report.llmNormalized());
        assertEquals(1, report.failed(), "LLM 产出非法 JSON 不得采用");
        assertEquals(2, llmCalls.get());
        assertEquals(1, report.backfilled());
        // 成功行回填为规范化名；失败行保留原文
        assertEquals(1, cacheStore.findByComment(SYSTEM, "额度状态").size());
        assertEquals(1, cacheStore.findByComment(SYSTEM, "交易类型？？未知").size());
    }

    @Test
    void llmDisabledOnlyDeterministicRuns() {
        seed(new CacheStore.Row("t_a", "cust_no", "varchar(64)", "客户 编号"),
                new CacheStore.Row("t_a", "gender", "char(2)", "性别？？详见附件【2023版】"));

        NormalizeCommentService.Report report = new NormalizeCommentService(
                cacheStore, ncStore, null).normalize(SYSTEM, 20, 0);
        assertEquals(1, report.deterministic());
        assertEquals(1, report.failed(), "LLM 关闭时无法解析的保留原文待人工");
    }

    @Test
    void rerunSkipsNormalizedWithoutCallingLlmAgain() {
        AtomicInteger llmCalls = new AtomicInteger();
        seed(new CacheStore.Row("t_a", "cust_no", "varchar(64)", "客户 编号"),
                new CacheStore.Row("t_a", "quota_status", "char(2)", "额度状态？？详见附件【2023版】"));
        Map<String, String> results = Map.of(
                "额度状态？？详见附件【2023版】", "{\"name\":\"额度状态\"}");

        try (LlmAssistService llm = llmWith(results, llmCalls)) {
            new NormalizeCommentService(cacheStore, ncStore, llm).normalize(SYSTEM, 20, 0);
            int callsAfterFirstRun = llmCalls.get();
            NormalizeCommentService.Report second =
                    new NormalizeCommentService(cacheStore, ncStore, llm)
                            .normalize(SYSTEM, 20, 0);
            assertEquals(2, second.skipped());
            assertEquals(0, second.deterministic());
            assertEquals(0, second.llmNormalized());
            assertEquals(callsAfterFirstRun, llmCalls.get(), "同键同原文重跑不得重复调 LLM");
        }
    }

    @Test
    void refreshBackfillsByKeyWithoutRerunningNormalization() {
        AtomicInteger llmCalls = new AtomicInteger();
        seed(new CacheStore.Row("t_a", "cust_no", "varchar(64)", "客户 编号"));
        try (LlmAssistService llm = llmWith(Map.of(), llmCalls)) {
            new NormalizeCommentService(cacheStore, ncStore, llm).normalize(SYSTEM, 20, 0);
        }
        assertEquals(1, cacheStore.findByComment(SYSTEM, "客户编号").size());

        // 模拟 Schema 缓存刷新（同原文重灌）：replaceSystem 自动按键回填，不重新跑规范化
        seed(new CacheStore.Row("t_a", "cust_no", "varchar(64)", "客户 编号"));
        assertEquals(1, cacheStore.findByComment(SYSTEM, "客户编号").size());
        assertEquals(1, ncStore.count(SYSTEM));
    }

    @Test
    void staleEntryIsNotBackfilledWhenSourceCommentChanged() {
        seed(new CacheStore.Row("t_a", "cust_no", "varchar(64)", "客户 编号"));
        new NormalizeCommentService(cacheStore, ncStore, null).normalize(SYSTEM, 20, 0);
        assertEquals(1, cacheStore.findByComment(SYSTEM, "客户编号").size());

        // 源系统 COMMENT 已变更：旧规范化结果不得回填（防陈旧）
        seed(new CacheStore.Row("t_a", "cust_no", "varchar(64)", "客户编号（新口径）"));
        assertEquals(1, cacheStore.findByComment(SYSTEM, "客户编号（新口径）").size());
        assertTrue(cacheStore.findByComment(SYSTEM, "客户编号").isEmpty());
    }
}
