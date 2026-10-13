package com.dbagent.engine.decision;

/**
 * 来源徽章：逐字段标注定义来源。
 */
public enum Badge {

    REUSE("复用·本系统"),
    STANDARDIZED("贯标·字典"),
    DERIVED("推导·待登记"),
    LLM_CANDIDATE("LLM·候选"),
    NEEDS_REVIEW("需人工复核");

    private final String display;

    Badge(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }
}
