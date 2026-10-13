package com.dbagent.engine.rules;

import java.util.List;
import java.util.Map;

/**
 * 类型映射规则（type-mapping.yaml）：数据类型 10 类 → 物理类型，及推导路径结尾类词推断。
 */
public class TypeMappingRules {

    public Map<String, TypeDef> types = Map.of();
    public Map<String, String> endings = Map.of();
    public String defaultType = "文本类";

    public static class TypeDef {
        public String physical;
        public Integer length;
        public List<Integer> precision;
        public String defaultValue;
        public String dataFormat;
    }

    /** 推导路径：按中文名结尾类词最长匹配推断数据类型，未命中取兜底类型。 */
    public String inferDataType(String chineseName) {
        String best = null;
        for (Map.Entry<String, String> e : endings.entrySet()) {
            if (chineseName.endsWith(e.getKey())
                    && (best == null || e.getKey().length() > best.length())) {
                best = e.getKey();
            }
        }
        return best == null ? defaultType : endings.get(best);
    }

    public TypeDef type(String dataType) {
        TypeDef def = types.get(dataType);
        if (def == null) {
            throw new IllegalStateException("类型映射缺失数据类型: " + dataType);
        }
        return def;
    }

    /**
     * 推导物理类型文本。用户手填长度/精度仅在推导/LLM 路径生效（复用命中时由决策链忽略）。
     */
    public String physicalType(String dataType, Integer userLength, Integer userPrecision) {
        TypeDef def = type(dataType);
        return switch (def.physical) {
            case "VARCHAR" -> "VARCHAR(" + (userLength != null ? userLength : def.length) + ")";
            case "TINYINT" -> "TINYINT(" + def.length + ")";
            case "DECIMAL" -> {
                List<Integer> p = (userLength != null && userPrecision != null)
                        ? List.of(userLength, userPrecision)
                        : def.precision;
                yield "DECIMAL(" + p.get(0) + "," + p.get(1) + ")";
            }
            default -> def.physical;
        };
    }

    /** 字面默认值（SQL 文本，如 '' / 0 / CURRENT_TIMESTAMP）；日期时间类返回 null 表示不写 DEFAULT。 */
    public String defaultValue(String dataType) {
        return type(dataType).defaultValue;
    }

    /** 待登记清单的数据格式（按有效长度/精度展开模板）。 */
    public String dataFormat(String dataType, Integer userLength, Integer userPrecision) {
        TypeDef def = type(dataType);
        String fmt = def.dataFormat;
        if (fmt == null) {
            return "";
        }
        if (fmt.contains("{length}")) {
            int eff = userLength != null ? userLength : (def.length != null ? def.length : 0);
            return fmt.replace("{length}", String.valueOf(eff));
        }
        if ("DECIMAL".equals(def.physical)) {
            List<Integer> p = (userLength != null && userPrecision != null)
                    ? List.of(userLength, userPrecision)
                    : def.precision;
            return (p.get(0) - p.get(1)) + "(" + p.get(1) + ")";
        }
        return fmt;
    }
}
