package com.dbagent.engine.decision;

import com.dbagent.engine.cache.CacheSupply;

import java.util.List;

/**
 * 单字段决策结果。englishName/dataType/physicalType 在"需人工复核"时可为 null。
 * codeList 为代码类完整代码清单（随生成结果单独输出，不静默截断）；
 * commentSuffix 为 DDL COMMENT 摘要后缀（代码类前 5 项 + "等共 N 项"）；
 * candidates 为复用多命中定义不一致时的全量候选（供用户选择）。
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
        List<CacheSupply.CachedField> candidates) {

    public static FieldDecision reuse(String chineseName, String englishName, String physicalType) {
        return new FieldDecision(chineseName, englishName, null, physicalType,
                Badge.REUSE, null, null, null, null);
    }

    public static FieldDecision standardized(String chineseName, String englishName, String dataType,
                                             String physicalType, List<CacheSupply.Code> codeList,
                                             String commentSuffix) {
        return new FieldDecision(chineseName, englishName, dataType, physicalType,
                Badge.STANDARDIZED, null, codeList, commentSuffix, null);
    }

    public static FieldDecision derived(String chineseName, String englishName, String dataType,
                                        String physicalType) {
        return new FieldDecision(chineseName, englishName, dataType, physicalType,
                Badge.DERIVED, null, null, null, null);
    }

    public static FieldDecision needsReview(String chineseName, String reason) {
        return needsReview(chineseName, reason, null);
    }

    public static FieldDecision needsReview(String chineseName, String reason,
                                            List<CacheSupply.CachedField> candidates) {
        return new FieldDecision(chineseName, null, null, null,
                Badge.NEEDS_REVIEW, reason, null, null, candidates);
    }

    public boolean isUsable() {
        return badge != Badge.NEEDS_REVIEW;
    }
}
