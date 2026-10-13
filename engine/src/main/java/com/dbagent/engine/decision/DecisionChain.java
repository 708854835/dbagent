package com.dbagent.engine.decision;

import com.dbagent.engine.cache.CacheSupply;
import com.dbagent.engine.dict.AbbreviationDictionary;
import com.dbagent.engine.form.FormField;
import com.dbagent.engine.rules.DataFormat;
import com.dbagent.engine.rules.NamingRules;
import com.dbagent.engine.rules.TypeMappingRules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 三级决策骨架：本系统复用 → 字典贯标 → 缩写词典推导。
 * 复用级在复用工单完整落地；词典未覆盖词转 LLM 辅助在 LLM 工单接入，本切片标"需人工复核"占位。
 */
public final class DecisionChain {

    /** 代码类 COMMENT 摘要的最大清单项数（完整清单随生成结果单独输出） */
    private static final int CODE_COMMENT_MAX_ITEMS = 5;

    private final CacheSupply cacheSupply;
    private final AbbreviationDictionary fieldAbbrev;
    private final TypeMappingRules typeMapping;
    private final NamingRules naming;

    public DecisionChain(CacheSupply cacheSupply, AbbreviationDictionary fieldAbbrev,
                         TypeMappingRules typeMapping, NamingRules naming) {
        this.cacheSupply = cacheSupply;
        this.fieldAbbrev = fieldAbbrev;
        this.typeMapping = typeMapping;
        this.naming = naming;
    }

    public FieldDecision decide(FormField field) {
        String chineseName = field.chineseName.trim();

        // ① 本系统复用：精确匹配存量字段 COMMENT 中文名（复用工单完整落地候选/冲突处理）
        List<CacheSupply.CachedField> hits = cacheSupply.findSchemaFields(chineseName);
        if (hits.size() == 1) {
            CacheSupply.CachedField hit = hits.get(0);
            // ADR-0001：照抄存量英文名与类型，忽略手填长度/精度
            return FieldDecision.reuse(chineseName, hit.englishName(), hit.physicalType());
        }
        if (hits.size() > 1) {
            boolean identical = hits.stream().allMatch(h ->
                    h.englishName().equals(hits.get(0).englishName())
                            && h.physicalType().equals(hits.get(0).physicalType()));
            if (identical) {
                CacheSupply.CachedField hit = hits.get(0);
                return FieldDecision.reuse(chineseName, hit.englishName(), hit.physicalType());
            }
            return FieldDecision.needsReview(chineseName, "复用命中多个定义不一致的存量字段，请人工选择");
        }

        // ② 字典贯标：公共集优先于自治级；存量公共集兼容采用（选词结构照抄，输出统一小写）
        List<CacheSupply.DictItem> items = new ArrayList<>(cacheSupply.findDictItems(chineseName));
        if (!items.isEmpty()) {
            items.sort(Comparator.comparingInt(i -> i.isPublicSet() ? 0 : 1));
            CacheSupply.DictItem item = items.get(0);
            if (item.formatIllegal()) {
                return FieldDecision.needsReview(chineseName,
                        "命中格式非法字典条目: " + item.id());
            }
            String englishName = item.englishName().toLowerCase(Locale.ROOT);
            int maxCodeLen = item.codeList().stream()
                    .mapToInt(c -> c.code() == null ? 0 : c.code().length())
                    .max().orElse(0);
            DataFormat fmt = DataFormat.parse(item.dataFormat());
            String physical = typeMapping.physicalFromDict(item.dataType(), fmt, maxCodeLen);
            return FieldDecision.standardized(chineseName, englishName, item.dataType(), physical,
                    item.codeList(), codeCommentSuffix(item));
        }

        // ③ 缩写词典推导
        AbbreviationDictionary.Derivation derivation = fieldAbbrev.derive(chineseName);
        if (!derivation.fullyCovered()) {
            // LLM 辅助接入前占位：标"需人工复核"，不静默采用
            return FieldDecision.needsReview(chineseName,
                    "缩写词典未覆盖: " + String.join("/", derivation.uncovered()));
        }
        String englishName = derivation.joined();
        if (englishName.length() > naming.field.maxChars) {
            return FieldDecision.needsReview(chineseName,
                    "推导英文名超 " + naming.field.maxChars + " 字符: " + englishName);
        }
        if (naming.field.reservedWords.contains(englishName)
                || !Pattern.matches(naming.field.pattern, englishName)) {
            return FieldDecision.needsReview(chineseName, "推导英文名不合规: " + englishName);
        }
        String dataType = typeMapping.inferDataType(chineseName);
        String physical = typeMapping.physicalType(dataType, field.length, field.precision);
        return FieldDecision.derived(chineseName, englishName, dataType, physical);
    }

    /** 代码类 COMMENT 摘要：前 5 项 + "等共 N 项"（完整清单随生成结果单独输出）。 */
    private static String codeCommentSuffix(CacheSupply.DictItem item) {
        if (!"代码类".equals(item.dataType()) || item.codeList().isEmpty()) {
            return null;
        }
        String head = item.codeList().stream()
                .limit(CODE_COMMENT_MAX_ITEMS)
                .map(c -> c.code() + "=" + c.name())
                .collect(Collectors.joining("，"));
        int total = item.codeList().size();
        return total > CODE_COMMENT_MAX_ITEMS
                ? head + "，等共 " + total + " 项"
                : head;
    }
}
