package com.dbagent.engine.decision;

import com.dbagent.engine.cache.CacheSupply;
import com.dbagent.engine.llm.LlmNamingProvider;

import java.util.List;

/**
 * 单字段决策结果。englishName/dataType/physicalType 在"需人工复核"时可为 null。
 * codeList 为代码类完整代码清单（随生成结果单独输出，不静默截断）；
 * commentSuffix 为 DDL COMMENT 摘要后缀（代码类前 5 项 + "等共 N 项"）；
 * candidates 为复用多命中定义不一致时的全量候选（供用户选择）；
 * llmEligible 标记词典未覆盖、可走 LLM 辅助的复核字段；
 * llmCandidates 为 LLM 建议候选（含置信度与理由，供人工参考）。
 */
public record FieldDecision(
        String chineseName,
        String englishName,
        String dataType,
        String physicalType,
        Badge badge,
        String note,
        List<CacheSupply.Code> codeList,
        String commentSuffix,
        List<CacheSupply.CachedField> candidates,
        boolean llmEligible,
        List<LlmNamingProvider.Candidate> llmCandidates) {

    public static FieldDecision reuse(String chineseName, String englishName, String physicalType) {
        return new FieldDecision(chineseName, englishName, null, physicalType,
                Badge.REUSE, null, null, null, null, false, null);
    }

    /** 已确认候选复用：用户复核通过的定义照抄（同上下文指纹 + 字典快照一致）。 */
    public static FieldDecision confirmed(String chineseName, String englishName, String dataType,
                                          String physicalType, List<CacheSupply.Code> codeList) {
        return new FieldDecision(chineseName, englishName, dataType, physicalType,
                Badge.REUSE, "已确认候选复用（上下文一致，未走 LLM）", codeList, null, null, false, null);
    }

    /** 用户锁定字段：增量重生成时照抄表单携带定义，不重算。 */
    public static FieldDecision locked(String chineseName, String englishName, String dataType,
                                       String physicalType) {
        return new FieldDecision(chineseName, englishName, dataType, physicalType,
                Badge.REUSE, "用户锁定字段（增量重生成未重算）", null, null, null, false, null);
    }

    public static FieldDecision standardized(String chineseName, String englishName, String dataType,
                                             String physicalType, List<CacheSupply.Code> codeList,
                                             String commentSuffix) {
        return new FieldDecision(chineseName, englishName, dataType, physicalType,
                Badge.STANDARDIZED, null, codeList, commentSuffix, null, false, null);
    }

    public static FieldDecision derived(String chineseName, String englishName, String dataType,
                                        String physicalType) {
        return new FieldDecision(chineseName, englishName, dataType, physicalType,
                Badge.DERIVED, null, null, null, null, false, null);
    }

    /** LLM 唯一高置信候选被采纳：徽章 LLM·候选，note 记录置信度与理由。 */
    public static FieldDecision llm(String chineseName, String englishName, String dataType,
                                    String physicalType, String note,
                                    List<LlmNamingProvider.Candidate> llmCandidates) {
        return new FieldDecision(chineseName, englishName, dataType, physicalType,
                Badge.LLM_CANDIDATE, note, null, null, null, false, llmCandidates);
    }

    public static FieldDecision needsReview(String chineseName, String reason) {
        return needsReview(chineseName, reason, null);
    }

    public static FieldDecision needsReview(String chineseName, String reason,
                                            List<CacheSupply.CachedField> candidates) {
        return new FieldDecision(chineseName, null, null, null,
                Badge.NEEDS_REVIEW, reason, null, null, candidates, false, null);
    }

    /** 词典未覆盖：标"需人工复核"并允许后续走 LLM 辅助。 */
    public FieldDecision markLlmEligible() {
        return new FieldDecision(chineseName, englishName, dataType, physicalType,
                badge, note, codeList, commentSuffix, candidates, true, llmCandidates);
    }

    /** LLM 辅助后仍降级：保留 LLM 候选供人工参考，原因并入 note。 */
    public FieldDecision degradeLlm(String extraNote,
                                    List<LlmNamingProvider.Candidate> llmCandidates) {
        String merged = note == null || note.isBlank() ? extraNote : note + "；" + extraNote;
        return new FieldDecision(chineseName, null, null, null,
                Badge.NEEDS_REVIEW, merged, null, null, candidates, false, llmCandidates);
    }

    public boolean isUsable() {
        return badge != Badge.NEEDS_REVIEW;
    }
}
