package com.dbagent.engine.rules;

import java.util.List;
import java.util.Map;

/**
 * LLM 规则（llm.yaml）。enabled=false 时不调用任何 LLM，规则不能定的字段统一标"需人工复核"。
 * provider 目前仅支持 stub（演示/测试桩，无需真实 LLM）；真实 LLM 接入时扩展工厂。
 */
public class LlmRules {

    public boolean enabled = false;
    public String provider = "stub";
    public int maxConcurrentColumns = 5;
    public int singleColumnTimeoutSeconds = 3;
    public int overallTimeoutSeconds = 5;
    public double minConfidence = 0.8;
    /** 每字段候选上限（超出截断，按置信度降序保留） */
    public int maxCandidates = 3;
    public Stub stub = new Stub();

    /** 桩配置：可配置候选/延迟/异常，用于演示与测试（不接入真实 LLM）。 */
    public static class Stub {
        /** 默认响应延迟（毫秒） */
        public long latencyMillis = 0;
        /** 慢响应字段名单（命中后用 slowLatencyMillis，演示超时降级） */
        public List<String> slowNames = List.of();
        public long slowLatencyMillis = 6000;
        /** 模拟调用失败的字段名单 */
        public List<String> failNames = List.of();
        /** 预置候选：字段中文名 → 候选清单；未配置的字段返回空候选 */
        public Map<String, List<StubCandidate>> candidates = Map.of();
        /** 预置表名候选：表中文名 → 候选清单（小写连写、不含 t_ 前缀） */
        public Map<String, List<StubCandidate>> tableCandidates = Map.of();
        /** 预置 COMMENT 规范化结果：原始 COMMENT → 标准 JSON 字符串；未配置的抛异常模拟失败 */
        public Map<String, String> commentResults = Map.of();
    }

    public static class StubCandidate {
        public String englishName;
        public double confidence;
        public String rationale;
    }
}
