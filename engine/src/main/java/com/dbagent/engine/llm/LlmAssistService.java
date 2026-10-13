package com.dbagent.engine.llm;

import com.dbagent.engine.llm.LlmNamingProvider.Candidate;
import com.dbagent.engine.llm.LlmNamingProvider.FieldContext;
import com.dbagent.engine.rules.LlmRules;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * LLM 辅助编排：并发 ≤ maxConcurrentColumns、单字段 ≤ singleColumnTimeoutSeconds、
 * 整表 ≤ overallTimeoutSeconds；超时/异常一律降级"需人工复核"，绝不静默采用。
 * 采纳规则：候选按置信度降序截取 ≤ maxCandidates 个，置信度 ≥ minConfidence 的候选
 * 恰好唯一时采纳，否则保留候选转人工。
 */
public final class LlmAssistService implements AutoCloseable {

    private final LlmNamingProvider provider;
    private final LlmRules rules;
    private final ExecutorService pool;

    public LlmAssistService(LlmNamingProvider provider, LlmRules rules) {
        this.provider = provider;
        this.rules = rules;
        this.pool = Executors.newFixedThreadPool(Math.max(1, rules.maxConcurrentColumns));
    }

    /**
     * @param candidates 截取后的候选（供人工参考，可为空）
     * @param adopted    被采纳的候选（唯一高置信），未采纳为 null
     * @param note       降级原因（采纳时为 null）
     */
    public record Outcome(List<Candidate> candidates, Candidate adopted, String note) {

        static Outcome degraded(String note) {
            return new Outcome(List.of(), null, note);
        }
    }

    /** 对一批字段并发请求 LLM 建议；按整表超时预算兜底，任一字段失败不影响其他字段。 */
    public Map<String, Outcome> assist(List<FieldContext> fields) {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(rules.overallTimeoutSeconds);
        Map<String, Future<Outcome>> futures = new LinkedHashMap<>();
        for (FieldContext f : fields) {
            futures.put(f.chineseName(), pool.submit(() -> call(f)));
        }
        Map<String, Outcome> outcomes = new LinkedHashMap<>();
        for (Map.Entry<String, Future<Outcome>> e : futures.entrySet()) {
            // 单字段预算与整表剩余预算取小（排队时间保守计入字段预算）
            long budgetMs = Math.min(
                    TimeUnit.SECONDS.toMillis(rules.singleColumnTimeoutSeconds),
                    Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            Future<Outcome> future = e.getValue();
            try {
                outcomes.put(e.getKey(), future.get(budgetMs, TimeUnit.MILLISECONDS));
            } catch (TimeoutException te) {
                future.cancel(true);
                outcomes.put(e.getKey(), Outcome.degraded("LLM 辅助超时，已降级为人工复核"));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                future.cancel(true);
                outcomes.put(e.getKey(), Outcome.degraded("LLM 辅助被中断，已降级为人工复核"));
            } catch (ExecutionException ee) {
                outcomes.put(e.getKey(), Outcome.degraded("LLM 辅助异常，已降级为人工复核"));
            }
        }
        return outcomes;
    }

    private Outcome call(FieldContext field) {
        List<Candidate> raw;
        try {
            raw = provider.suggest(field);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return Outcome.degraded("LLM 辅助超时，已降级为人工复核");
        } catch (Exception e) {
            return Outcome.degraded("LLM 辅助异常（" + e.getClass().getSimpleName()
                    + "），已降级为人工复核");
        }
        return select(raw);
    }

    /**
     * 表名辅助：表名缩写词典未覆盖词 → LLM 候选表名。
     * 单次调用，预算取单字段超时；超时/异常一律返回降级 Outcome，绝不抛出阻断生成。
     */
    public Outcome assistTableName(LlmNamingProvider.TableContext table) {
        Future<Outcome> future = pool.submit(() -> {
            List<Candidate> raw;
            try {
                raw = provider.suggestTableName(table);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return Outcome.degraded("LLM 表名辅助超时，已降级为人工复核");
            } catch (Exception e) {
                return Outcome.degraded("LLM 表名辅助异常（" + e.getClass().getSimpleName()
                        + "），已降级为人工复核");
            }
            return select(raw);
        });
        try {
            return future.get(rules.singleColumnTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            return Outcome.degraded("LLM 表名辅助超时，已降级为人工复核");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return Outcome.degraded("LLM 表名辅助被中断，已降级为人工复核");
        } catch (ExecutionException ee) {
            return Outcome.degraded("LLM 表名辅助异常，已降级为人工复核");
        }
    }

    /**
     * 离线 COMMENT 规范化兜底：返回 LLM 产出的标准 JSON 字符串。
     * 单次调用，预算取单字段超时；超时/异常/不支持一律返回 null（调用方保留原始 COMMENT）。
     */
    public String normalizeComment(String rawComment) {
        Future<String> future = pool.submit(() -> provider.normalizeComment(rawComment));
        try {
            return future.get(rules.singleColumnTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            future.cancel(true);
            return null;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return null;
        } catch (ExecutionException ee) {
            return null;
        }
    }

    /** 双闸门采纳：候选按置信度降序截取 ≤maxCandidates，≥minConfidence 恰好唯一才采纳。 */
    private Outcome select(List<Candidate> raw) {
        List<Candidate> candidates = (raw == null ? List.<Candidate>of() : raw).stream()
                .filter(c -> c != null && c.englishName() != null && !c.englishName().isBlank())
                .sorted(Comparator.comparingDouble(Candidate::confidence).reversed())
                .limit(Math.max(1, rules.maxCandidates))
                .toList();
        List<Candidate> high = candidates.stream()
                .filter(c -> c.confidence() >= rules.minConfidence)
                .toList();
        if (high.size() == 1) {
            return new Outcome(candidates, high.get(0), null);
        }
        if (high.isEmpty()) {
            return new Outcome(candidates, null,
                    "LLM 候选置信度均低于 " + rules.minConfidence + "，需人工复核");
        }
        return new Outcome(candidates, null,
                "LLM 高置信度候选 " + high.size() + " 个（非唯一），需人工复核");
    }

    @Override
    public void close() {
        pool.shutdownNow();
    }
}
