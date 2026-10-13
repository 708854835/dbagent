package com.dbagent.engine.cache;

import com.dbagent.engine.db.CacheStore;
import com.dbagent.engine.db.DictStore;

import java.util.List;

/**
 * SQLite 缓存供给：Schema 缓存按系统隔离（CacheStore），字典快照全局一份（DictStore）。
 * 生成完全离线读缓存。
 */
public final class SqliteCacheSupply implements CacheSupply {

    private final DictStore dictStore;
    private final CacheStore cacheStore;

    public SqliteCacheSupply(DictStore dictStore, CacheStore cacheStore) {
        this.dictStore = dictStore;
        this.cacheStore = cacheStore;
    }

    @Override
    public List<CachedField> findSchemaFields(String owningSystem, String chineseName) {
        if (owningSystem == null || owningSystem.isBlank()) {
            return List.of();
        }
        return cacheStore.findByComment(owningSystem, chineseName);
    }

    @Override
    public List<DictItem> findDictItems(String chineseName) {
        return dictStore.findByChineseName(chineseName);
    }
}
