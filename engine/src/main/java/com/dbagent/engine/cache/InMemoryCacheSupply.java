package com.dbagent.engine.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存 fake 缓存供给：默认全空（全部走推导/贯标路径）；
 * 测试可预置条目以验证复用/贯标缝。
 */
public final class InMemoryCacheSupply implements CacheSupply {

    private final Map<String, List<CachedField>> schemaFields = new HashMap<>();
    private final Map<String, List<DictItem>> dictItems = new HashMap<>();
    private final Map<String, List<ConfirmedCandidate>> confirmed = new HashMap<>();

    public InMemoryCacheSupply putSchemaField(String owningSystem, CachedField field) {
        schemaFields.computeIfAbsent(key(owningSystem, field.chineseName()),
                k -> new ArrayList<>()).add(field);
        return this;
    }

    public InMemoryCacheSupply putDictItem(DictItem item) {
        dictItems.computeIfAbsent(item.chineseName(), k -> new ArrayList<>()).add(item);
        return this;
    }

    public InMemoryCacheSupply putConfirmed(String owningSystem, ConfirmedCandidate candidate) {
        confirmed.computeIfAbsent(key(owningSystem, candidate.chineseName()),
                k -> new ArrayList<>()).add(candidate);
        return this;
    }

    @Override
    public List<CachedField> findSchemaFields(String owningSystem, String chineseName) {
        if (owningSystem == null || owningSystem.isBlank()) {
            return List.of();
        }
        return schemaFields.getOrDefault(key(owningSystem, chineseName), List.of());
    }

    @Override
    public List<DictItem> findDictItems(String chineseName) {
        return dictItems.getOrDefault(chineseName, List.of());
    }

    @Override
    public List<ConfirmedCandidate> findConfirmed(String owningSystem, String chineseName) {
        if (owningSystem == null || owningSystem.isBlank()) {
            return List.of();
        }
        return confirmed.getOrDefault(key(owningSystem, chineseName), List.of());
    }

    private static String key(String owningSystem, String chineseName) {
        // 精确匹配（忽略首尾空白，不做分词/相似度）
        return owningSystem + "\u0001" + chineseName.trim();
    }
}
