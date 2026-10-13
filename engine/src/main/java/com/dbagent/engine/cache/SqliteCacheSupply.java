package com.dbagent.engine.cache;

import com.dbagent.engine.db.DictStore;

import java.util.List;

/**
 * SQLite 缓存供给：字典快照经 DictStore 读取（全局一份）；
 * 本系统 Schema 缓存随后续复用工单接入（本实现暂返回空）。
 */
public final class SqliteCacheSupply implements CacheSupply {

    private final DictStore dictStore;

    public SqliteCacheSupply(DictStore dictStore) {
        this.dictStore = dictStore;
    }

    @Override
    public List<CachedField> findSchemaFields(String chineseName) {
        return List.of();
    }

    @Override
    public List<DictItem> findDictItems(String chineseName) {
        return dictStore.findByChineseName(chineseName);
    }
}
