package com.dbagent.engine.cache;

import com.dbagent.engine.db.CacheStore;
import com.dbagent.engine.db.ConfirmedStore;
import com.dbagent.engine.db.DictStore;

import java.util.List;

/**
 * SQLite 缓存供给：Schema 缓存按系统隔离（CacheStore），字典快照全局一份（DictStore），
 * 已确认候选按字典快照标记过滤失效（ConfirmedStore）。生成完全离线读缓存。
 */
public final class SqliteCacheSupply implements CacheSupply {

    private final DictStore dictStore;
    private final CacheStore cacheStore;
    private final ConfirmedStore confirmedStore;

    public SqliteCacheSupply(DictStore dictStore, CacheStore cacheStore) {
        this(dictStore, cacheStore, null);
    }

    public SqliteCacheSupply(DictStore dictStore, CacheStore cacheStore,
                             ConfirmedStore confirmedStore) {
        this.dictStore = dictStore;
        this.cacheStore = cacheStore;
        this.confirmedStore = confirmedStore;
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

    @Override
    public List<ConfirmedCandidate> findConfirmed(String owningSystem, String chineseName) {
        if (confirmedStore == null || owningSystem == null || owningSystem.isBlank()) {
            return List.of();
        }
        return confirmedStore.findValid(owningSystem, chineseName, dictStore.currentSnapshot());
    }
}
