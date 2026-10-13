package com.dbagent.engine.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存 fake 缓存供给：T1 切片默认全空（全部走推导路径）；
 * 测试可预置条目以验证复用/贯标缝。
 */
public final class InMemoryCacheSupply implements CacheSupply {

    private final Map<String, List<CachedField>> schemaFields = new HashMap<>();
    private final Map<String, List<DictItem>> dictItems = new HashMap<>();

    public InMemoryCacheSupply putSchemaField(CachedField field) {
        schemaFields.computeIfAbsent(field.chineseName(), k -> new ArrayList<>()).add(field);
        return this;
    }

    public InMemoryCacheSupply putDictItem(DictItem item) {
        dictItems.computeIfAbsent(item.chineseName(), k -> new ArrayList<>()).add(item);
        return this;
    }

    @Override
    public List<CachedField> findSchemaFields(String chineseName) {
        return schemaFields.getOrDefault(chineseName, List.of());
    }

    @Override
    public List<DictItem> findDictItems(String chineseName) {
        return dictItems.getOrDefault(chineseName, List.of());
    }
}
