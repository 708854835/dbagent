package com.dbagent.engine.decision;

import com.dbagent.engine.cache.CacheSupply;
import com.dbagent.engine.dict.AbbreviationDictionary;
import com.dbagent.engine.form.FormField;
import com.dbagent.engine.rules.NamingRules;
import com.dbagent.engine.rules.TypeMappingRules;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 三级决策骨架：本系统复用 → 字典贯标 → 缩写词典推导。
 * T1 切片：复用/贯标由内存 fake 供给（默认未命中），未命中全部走推导；
 * 词典未覆盖的词转 LLM 辅助在后续工单接入，本切片标"需人工复核"占位。
 */
public final class DecisionChain {

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

        // ① 本系统复用：精确匹配存量字段 COMMENT 中文名（T4 完整落地候选/冲突处理）
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

        // ② 字典贯标（T3 完整落地公共集优先/存量兼容/代码类处理）
        List<CacheSupply.DictItem> items = cacheSupply.findDictItems(chineseName);
        if (!items.isEmpty()) {
            CacheSupply.DictItem item = items.get(0);
            String physical = typeMapping.physicalType(item.dataType(), null, null);
            return FieldDecision.standardized(chineseName, item.englishName(), item.dataType(), physical);
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
}
