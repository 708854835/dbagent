package com.dbagent.engine.comment;

import com.dbagent.engine.db.CacheStore;
import com.dbagent.engine.db.NormalizedCommentStore;
import com.dbagent.engine.llm.LlmAssistService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线 COMMENT 规范化编排（CLI 薄壳直调）：
 * 先跑确定性清洗（CommentNormalizer），仅无法解析的才调 LLM 兜底转标准 JSON；
 * 按批限流；结果独立落库 normalized_comment（同键同原文跳过，重跑不重复调 LLM）；
 * 结束后按键回填当前 Schema 缓存。
 */
public final class NormalizeCommentService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CacheStore cacheStore;
    private final NormalizedCommentStore store;
    /** 可 null（LLM 关闭：仅确定性清洗，无法解析的保留原文待人工） */
    private final LlmAssistService llm;

    public NormalizeCommentService(CacheStore cacheStore, NormalizedCommentStore store,
                                   LlmAssistService llm) {
        this.cacheStore = cacheStore;
        this.store = store;
        this.llm = llm;
    }

    /**
     * @param total          缓存中非空 COMMENT 条数
     * @param deterministic  确定性清洗成功数
     * @param llmNormalized  LLM 兜底成功数
     * @param failed         确定性+LLM 均无法解析数（保留原文待人工）
     * @param skipped        已规范化跳过数（同键同原文，未重复调 LLM）
     * @param backfilled     结束后回填 Schema 缓存行数
     */
    public record Report(String owningSystem, int total, int deterministic, int llmNormalized,
                         int failed, int skipped, int backfilled) {

        public String summaryLine() {
            return "COMMENT 规范化完成: 系统 " + owningSystem + "，非空 COMMENT " + total
                    + " 条 → 确定性清洗 " + deterministic + "，LLM 兜底 " + llmNormalized
                    + "，无法解析 " + failed + "（保留原文），跳过已规范化 " + skipped
                    + "；回填缓存 " + backfilled + " 行";
        }
    }

    /**
     * @param batchSize           每批新处理条数（按批限流粒度）
     * @param batchIntervalMillis 批间暂停毫秒（0 表示不限流，测试用）
     */
    public Report normalize(String owningSystem, int batchSize, long batchIntervalMillis) {
        int effectiveBatch = Math.max(1, batchSize);
        int deterministic = 0;
        int llmNormalized = 0;
        int failed = 0;
        int skipped = 0;
        int processed = 0;
        List<CacheStore.Row> rows = cacheStore.listRows(owningSystem).stream()
                .filter(r -> !r.columnComment().isBlank())
                .toList();
        for (CacheStore.Row row : rows) {
            String raw = row.columnComment();
            if (store.isNormalized(owningSystem, row.tableName(), row.columnName(), raw)) {
                skipped++;
                continue;
            }
            CommentNormalizer.Normalized normalized = CommentNormalizer.clean(raw);
            String source = "deterministic";
            if (normalized == null) {
                if (llm == null) {
                    failed++;
                    continue;
                }
                normalized = parseLlmJson(llm.normalizeComment(raw));
                if (normalized == null) {
                    failed++;
                    continue;
                }
                source = "llm";
            }
            store.upsert(owningSystem, new NormalizedCommentStore.Entry(
                    row.tableName(), row.columnName(), raw,
                    normalized.name(), toJson(normalized), source));
            if ("llm".equals(source)) {
                llmNormalized++;
            } else {
                deterministic++;
            }
            processed++;
            if (batchIntervalMillis > 0 && processed % effectiveBatch == 0) {
                try {
                    Thread.sleep(batchIntervalMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        int backfilled = cacheStore.backfillNormalizedComments(owningSystem);
        return new Report(owningSystem, rows.size(), deterministic, llmNormalized,
                failed, skipped, backfilled);
    }

    /** LLM 产出标准 JSON 校验：name 必填非空，codes 可选且须为 字符串→字符串 对象。 */
    static CommentNormalizer.Normalized parseLlmJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = JSON.readTree(json);
            JsonNode name = root == null ? null : root.get("name");
            if (name == null || !name.isTextual() || name.asText().isBlank()) {
                return null;
            }
            Map<String, String> codes = null;
            JsonNode codesNode = root.get("codes");
            if (codesNode != null && !codesNode.isNull()) {
                if (!codesNode.isObject()) {
                    return null;
                }
                codes = new LinkedHashMap<>();
                var it = codesNode.fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (!e.getValue().isTextual()) {
                        return null;
                    }
                    codes.put(e.getKey(), e.getValue().asText());
                }
            }
            return new CommentNormalizer.Normalized(name.asText().trim(), codes);
        } catch (Exception e) {
            return null;
        }
    }

    private static String toJson(CommentNormalizer.Normalized normalized) {
        ObjectNode node = JSON.createObjectNode();
        node.put("name", normalized.name());
        if (normalized.codes() != null && !normalized.codes().isEmpty()) {
            ObjectNode codes = node.putObject("codes");
            normalized.codes().forEach(codes::put);
        }
        return node.toString();
    }
}
