package com.dbagent.engine.llm;

import com.dbagent.engine.llm.LlmNamingProvider.Candidate;
import com.dbagent.engine.llm.LlmNamingProvider.FieldContext;
import com.dbagent.engine.rules.LlmRules;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 编排：候选截取/唯一高置信采纳/并发上限/单字段与整表超时/异常降级。
 */
class LlmAssistServiceTest {

    private static LlmRules rules() {
        LlmRules r = new LlmRules();
        r.enabled = true;
        r.maxConcurrentColumns = 5;
        r.singleColumnTimeoutSeconds = 3;
        r.overallTimeoutSeconds = 5;
        r.minConfidence = 0.8;
        r.maxCandidates = 3;
        return r;
    }

    private static FieldContext ctx(String name) {
        return new FieldContext("测试台账", name, List.of());
    }

    @Test
    void uniqueHighConfidenceCandidateIsAdopted() {
        LlmNamingProvider provider = c -> List.of(
                new Candidate("spv", 0.92, "Special Purpose Vehicle 通用缩写"),
                new Candidate("sp_carrier", 0.55, "直译"));
        try (LlmAssistService service = new LlmAssistService(provider, rules())) {
            LlmAssistService.Outcome o = service.assist(List.of(ctx("特殊目的载体")))
                    .get("特殊目的载体");
            assertEquals("spv", o.adopted().englishName());
            assertNull(o.note());
            assertEquals(2, o.candidates().size());
            // 候选按置信度降序
            assertEquals("spv", o.candidates().get(0).englishName());
        }
    }

    @Test
    void multipleHighConfidenceCandidatesAreNotAdopted() {
        LlmNamingProvider provider = c -> List.of(
                new Candidate("trustee_org", 0.85, "受托 trustee"),
                new Candidate("entrust_org", 0.83, "受托 entrust"));
        try (LlmAssistService service = new LlmAssistService(provider, rules())) {
            LlmAssistService.Outcome o = service.assist(List.of(ctx("受托机构")))
                    .get("受托机构");
            assertNull(o.adopted());
            assertTrue(o.note().contains("非唯一"), o.note());
            assertEquals(2, o.candidates().size());
        }
    }

    @Test
    void allBelowThresholdAreNotAdopted() {
        LlmNamingProvider provider = c -> List.of(new Candidate("maybe_name", 0.5, "不确定"));
        try (LlmAssistService service = new LlmAssistService(provider, rules())) {
            LlmAssistService.Outcome o = service.assist(List.of(ctx("生僻词")))
                    .get("生僻词");
            assertNull(o.adopted());
            assertTrue(o.note().contains("置信度"), o.note());
            assertEquals(1, o.candidates().size());
        }
    }

    @Test
    void candidatesAreTruncatedToMaxAndSortedByConfidence() {
        LlmNamingProvider provider = c -> List.of(
                new Candidate("a", 0.1, "r1"),
                new Candidate("b", 0.9, "r2"),
                new Candidate("c", 0.3, "r3"),
                new Candidate("d", 0.2, "r4"),
                new Candidate("e", 0.05, "r5"));
        try (LlmAssistService service = new LlmAssistService(provider, rules())) {
            LlmAssistService.Outcome o = service.assist(List.of(ctx("多候选词")))
                    .get("多候选词");
            assertEquals(3, o.candidates().size());
            assertEquals(List.of("b", "c", "d"),
                    o.candidates().stream().map(Candidate::englishName).toList());
            assertEquals("b", o.adopted().englishName());
        }
    }

    @Test
    void slowProviderIsDegradedBySingleColumnTimeout() {
        LlmRules r = rules();
        r.singleColumnTimeoutSeconds = 1;
        LlmNamingProvider slow = c -> {
            Thread.sleep(5_000);
            return List.of(new Candidate("too_late", 0.99, "迟到"));
        };
        try (LlmAssistService service = new LlmAssistService(slow, r)) {
            long start = System.nanoTime();
            LlmAssistService.Outcome o = service.assist(List.of(ctx("估值基准日")))
                    .get("估值基准日");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertNull(o.adopted());
            assertTrue(o.note().contains("超时"), o.note());
            assertTrue(o.candidates().isEmpty());
            assertTrue(elapsedMs < 4_000, "降级应及时返回，实际 " + elapsedMs + "ms");
        }
    }

    @Test
    void overallTimeoutBudgetBoundsWholeTable() {
        LlmRules r = rules();
        r.singleColumnTimeoutSeconds = 10;
        r.overallTimeoutSeconds = 1;
        LlmNamingProvider slow = c -> {
            Thread.sleep(5_000);
            return List.of(new Candidate("too_late", 0.99, "迟到"));
        };
        try (LlmAssistService service = new LlmAssistService(slow, r)) {
            long start = System.nanoTime();
            Map<String, LlmAssistService.Outcome> outcomes = service.assist(
                    List.of(ctx("慢字段一"), ctx("慢字段二"), ctx("慢字段三")));
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertEquals(3, outcomes.size());
            assertTrue(outcomes.values().stream().allMatch(o -> o.adopted() == null));
            assertTrue(elapsedMs < 4_500, "整表预算应兜底，实际 " + elapsedMs + "ms");
        }
    }

    @Test
    void providerExceptionIsDegradedNotThrown() {
        LlmNamingProvider broken = c -> {
            throw new IllegalStateException("LLM 服务不可用");
        };
        try (LlmAssistService service = new LlmAssistService(broken, rules())) {
            LlmAssistService.Outcome o = service.assist(List.of(ctx("任意词")))
                    .get("任意词");
            assertNull(o.adopted());
            assertTrue(o.note().contains("异常"), o.note());
        }
    }

    @Test
    void concurrencyNeverExceedsConfiguredLimit() {
        LlmRules r = rules();
        r.maxConcurrentColumns = 2;
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maxSeen = new AtomicInteger();
        LlmNamingProvider counting = c -> {
            int cur = active.incrementAndGet();
            maxSeen.accumulateAndGet(cur, Math::max);
            try {
                Thread.sleep(300);
            } finally {
                active.decrementAndGet();
            }
            return List.of();
        };
        try (LlmAssistService service = new LlmAssistService(counting, r)) {
            List<FieldContext> fields = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                fields.add(ctx("字段" + i));
            }
            service.assist(fields);
            assertEquals(2, maxSeen.get(), "并发峰值应恰为配置上限");
        }
    }

    @Test
    void tableNameUniqueHighConfidenceIsAdopted() {
        LlmNamingProvider provider = new LlmNamingProvider() {
            @Override
            public List<Candidate> suggest(FieldContext context) {
                return List.of();
            }

            @Override
            public List<Candidate> suggestTableName(TableContext context) {
                return List.of(new Candidate("spvpool", 0.91, "SPV + pool"));
            }
        };
        try (LlmAssistService service = new LlmAssistService(provider, rules())) {
            LlmAssistService.Outcome o = service.assistTableName(
                    new LlmNamingProvider.TableContext("特殊目的载体资产池", null));
            assertEquals("spvpool", o.adopted().englishName());
            assertNull(o.note());
            assertEquals(1, o.candidates().size());
        }
    }

    @Test
    void tableNameExceptionIsDegradedNotThrown() {
        LlmNamingProvider provider = new LlmNamingProvider() {
            @Override
            public List<Candidate> suggest(FieldContext context) {
                return List.of();
            }

            @Override
            public List<Candidate> suggestTableName(TableContext context) {
                throw new IllegalStateException("LLM 服务不可用");
            }
        };
        try (LlmAssistService service = new LlmAssistService(provider, rules())) {
            LlmAssistService.Outcome o = service.assistTableName(
                    new LlmNamingProvider.TableContext("任意表", null));
            assertNull(o.adopted());
            assertTrue(o.note().contains("异常"), o.note());
        }
    }

    @Test
    void normalizeCommentTimesOutAndDegradesToNull() {
        LlmRules r = rules();
        r.singleColumnTimeoutSeconds = 1;
        LlmNamingProvider provider = new LlmNamingProvider() {
            @Override
            public List<Candidate> suggest(FieldContext context) {
                return List.of();
            }

            @Override
            public String normalizeComment(String rawComment) throws InterruptedException {
                Thread.sleep(6_000);
                return "{\"name\":\"不应返回\"}";
            }
        };
        try (LlmAssistService service = new LlmAssistService(provider, r)) {
            assertNull(service.normalizeComment("脏 COMMENT"));
        }
    }
}
