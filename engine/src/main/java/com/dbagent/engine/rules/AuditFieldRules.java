package com.dbagent.engine.rules;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 审计字段规则（audit-fields.yaml）：自动补齐清单 + 中文名别名去重。
 */
public class AuditFieldRules {

    public List<AuditField> fields = List.of();

    public static class AuditField {
        public String englishName;
        public String physicalType;
        public String comment;
        public List<String> aliasList = List.of();
        public String extra;
        public String defaultValue;
    }

    /** 全部中文名别名集合（用于用户录入字段去重判定）。 */
    public Set<String> allAliases() {
        return fields.stream()
                .flatMap(f -> f.aliasList.stream())
                .collect(Collectors.toSet());
    }
}
