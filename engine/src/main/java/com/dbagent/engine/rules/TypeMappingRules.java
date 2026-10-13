package com.dbagent.engine.rules;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 类型映射规则（type-mapping.yaml）：数据类型 10 类 → 物理类型，推导路径结尾类词推断，
 * 字典贯标的数据格式合法性校验与格式→物理类型映射。
 */
public class TypeMappingRules {

    public Map<String, TypeDef> types = Map.of();
    public Map<String, String> endings = Map.of();
    public String defaultType = "文本类";
    public List<FormatAlias> formatAliases = List.of();

    public static class TypeDef {
        public String physical;
        public Integer length;
        public List<Integer> precision;
        public String defaultValue;
        public String dataFormat;
        public List<String> allowedFormats = List.of();
    }

    public static class FormatAlias {
        public String pattern;
        public String replacement;
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

    /** 业务口述记法 → 标准记法（如 6!n → n6），首条命中规则生效。 */
    public String normalizeFormat(String format) {
        if (format == null) {
            return null;
        }
        String f = format.trim();
        for (FormatAlias alias : formatAliases) {
            if (Pattern.compile(alias.pattern).matcher(f).matches()) {
                return f.replaceAll(alias.pattern, alias.replacement);
            }
        }
        return f;
    }

    /** 数据格式合法性校验：按数据类型的 allowedFormats 约束判定。 */
    public boolean isFormatLegal(String dataType, String format) {
        DataFormat fmt = DataFormat.parse(format);
        if (fmt == null) {
            return false;
        }
        List<String> allowed = type(dataType).allowedFormats;
        return switch (fmt.kind()) {
            case DATE -> allowed.contains("DATE");
            case TIME -> allowed.contains("TIME");
            case DATETIME -> allowed.contains("DATETIME");
            case CHAR_SEQ -> fmt.fixed()
                    ? allowed.contains("CHAR_SEQ_FIXED")
                    : allowed.contains("CHAR_SEQ_MAX");
            case DECIMAL -> fmt.fracDigits() > 0
                    ? allowed.contains("DECIMAL") || allowed.contains("DECIMAL_FRAC_GT0")
                    : allowed.contains("DECIMAL");
        };
    }

    /**
     * 贯标路径：字典标准类型 + 数据格式 → 物理类型。
     * 代码类长度按取值设计（codeListMaxLen 来自代码清单最大取值长度），不预留。
     */
    public String physicalFromDict(String dataType, DataFormat fmt, int codeListMaxLen) {
        return switch (fmt.kind()) {
            case DATE -> "DATE";
            case TIME -> "TIME";
            case DATETIME -> "DATETIME";
            case DECIMAL -> "DECIMAL(" + (fmt.intDigits() + fmt.fracDigits()) + ","
                    + fmt.fracDigits() + ")";
            case CHAR_SEQ -> {
                if ("标志类".equals(dataType)) {
                    yield "TINYINT(1)";
                }
                int len = codeListMaxLen > 0 ? codeListMaxLen : fmt.length();
                yield "VARCHAR(" + len + ")";
            }
        };
    }
}
