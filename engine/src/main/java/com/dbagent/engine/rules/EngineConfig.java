package com.dbagent.engine.rules;

import com.dbagent.engine.dict.AbbreviationDictionary;

/**
 * 引擎全部规则配置聚合：config/rules 五 YAML + config/dict 两套缩写词典。
 * 改配置重启生效（启动时一次性装载）。
 */
public record EngineConfig(
        NamingRules naming,
        TypeMappingRules typeMapping,
        ConstraintRules constraints,
        AuditFieldRules auditFields,
        LlmRules llm,
        AbbreviationDictionary tableAbbrev,
        AbbreviationDictionary fieldAbbrev) {
}
