package com.dbagent.engine.cache;

import java.util.List;

/**
 * 缓存供给缝：决策链经此接口读本系统 Schema 缓存 / 字典快照。
 * T1 切片以内存 fake 注入（未命中全部走推导）；JDBC 拉取与 SQLite 落库在后续工单接入。
 */
public interface CacheSupply {

    /**
     * 本系统 Schema 缓存：按录入中文名与存量字段 COMMENT 中文名精确匹配。
     * 命中多条且定义不一致时返回全部候选，由决策链标"需人工复核"。
     */
    List<CachedField> findSchemaFields(String chineseName);

    /** 字典快照：按中文名查字典项（公共集/自治级）。 */
    List<DictItem> findDictItems(String chineseName);

    /** 存量字段定义（英文名 + 物理类型照抄，见 ADR-0001）。 */
    record CachedField(String chineseName, String englishName, String physicalType) {
    }

    /** 字典项（本切片最小结构；代码清单/编码规则等随贯标工单扩展）。 */
    record DictItem(String chineseName, String englishName, String dataType, String dataFormat,
                    String importance) {
    }
}
