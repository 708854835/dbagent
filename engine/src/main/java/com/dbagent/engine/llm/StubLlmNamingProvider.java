package com.dbagent.engine.llm;

import com.dbagent.engine.rules.LlmRules;

import java.util.List;

/**
 * 桩 LLM 提供者：按 llm.yaml 的 stub 配置返回预置候选，可模拟延迟与异常。
 * 用于演示与测试（不接入真实 LLM）；真实实现接入时实现同一接口即可。
 */
public final class StubLlmNamingProvider implements LlmNamingProvider {

    private final LlmRules.Stub config;

    public StubLlmNamingProvider(LlmRules.Stub config) {
        this.config = config;
    }

    @Override
    public List<Candidate> suggest(FieldContext context) throws Exception {
        simulateLatencyAndFailure(context.chineseName());
        return toCandidates(config.candidates.getOrDefault(context.chineseName(), List.of()));
    }

    @Override
    public List<Candidate> suggestTableName(TableContext context) throws Exception {
        simulateLatencyAndFailure(context.tableChineseName());
        return toCandidates(config.tableCandidates.getOrDefault(context.tableChineseName(), List.of()));
    }

    @Override
    public String normalizeComment(String rawComment) throws Exception {
        simulateLatencyAndFailure(rawComment);
        String result = config.commentResults.get(rawComment);
        if (result == null) {
            throw new IllegalStateException("stub 无预置规范化结果: " + rawComment);
        }
        return result;
    }

    private void simulateLatencyAndFailure(String name) throws InterruptedException {
        long latency = config.slowNames.contains(name) ? config.slowLatencyMillis : config.latencyMillis;
        if (latency > 0) {
            Thread.sleep(latency);
        }
        if (config.failNames.contains(name)) {
            throw new IllegalStateException("stub 模拟 LLM 调用失败: " + name);
        }
    }

    private List<Candidate> toCandidates(List<LlmRules.StubCandidate> configured) {
        return configured.stream()
                .map(c -> new Candidate(c.englishName, c.confidence, c.rationale))
                .toList();
    }
}
