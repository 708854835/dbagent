package com.dbagent.engine.decision;

/**
 * 单字段决策结果。englishName/dataType/physicalType 在"需人工复核"时可为 null。
 */
public record FieldDecision(
        String chineseName,
        String englishName,
        String dataType,
        String physicalType,
        Badge badge,
        String note) {

    public static FieldDecision reuse(String chineseName, String englishName, String physicalType) {
        return new FieldDecision(chineseName, englishName, null, physicalType, Badge.REUSE, null);
    }

    public static FieldDecision standardized(String chineseName, String englishName, String dataType,
                                             String physicalType) {
        return new FieldDecision(chineseName, englishName, dataType, physicalType, Badge.STANDARDIZED, null);
    }

    public static FieldDecision derived(String chineseName, String englishName, String dataType,
                                        String physicalType) {
        return new FieldDecision(chineseName, englishName, dataType, physicalType, Badge.DERIVED, null);
    }

    public static FieldDecision needsReview(String chineseName, String reason) {
        return new FieldDecision(chineseName, null, null, null, Badge.NEEDS_REVIEW, reason);
    }

    public boolean isUsable() {
        return badge != Badge.NEEDS_REVIEW;
    }
}
