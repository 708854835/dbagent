package com.dbagent.engine.llm;

import com.dbagent.engine.rules.LlmRules;

/**
 * 按 llm.yaml 创建 LLM 辅助编排：enabled=false 返回 null（生成服务走纯规则路径）。
 * 目前仅支持 stub；真实 LLM 接入时在此扩展 provider 分支。
 */
public final class LlmProviderFactory {

    private LlmProviderFactory() {
    }

    public static LlmAssistService create(LlmRules rules) {
        if (rules == null || !rules.enabled) {
            return null;
        }
        if ("stub".equalsIgnoreCase(rules.provider)) {
            return new LlmAssistService(new StubLlmNamingProvider(rules.stub), rules);
        }
        throw new IllegalStateException("未知 LLM provider: " + rules.provider);
    }
}
