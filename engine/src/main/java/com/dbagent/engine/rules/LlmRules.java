package com.dbagent.engine.rules;

/**
 * LLM 规则（llm.yaml）。T1 切片为占位：enabled=false 时不调用任何 LLM，
 * 规则不能定的字段统一标"需人工复核"。
 */
public class LlmRules {

    public boolean enabled = false;
    public int maxConcurrentColumns = 5;
    public int singleColumnTimeoutSeconds = 3;
    public int overallTimeoutSeconds = 5;
    public double minConfidence = 0.8;
}
