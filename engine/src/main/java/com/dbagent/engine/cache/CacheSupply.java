package com.dbagent.engine.cache;

import java.util.List;

/**
 * 缓存供给缝：决策链经此接口读本系统 Schema 缓存 / 字典快照。
 * Schema 缓存按"系统"维度隔离；字典快照全局一份（SQLite 供给）。
 */
public interface CacheSupply {

    /**
     * 本系统 Schema 缓存：按录入中文名与存量字段 COMMENT 中文名精确匹配
     * （精确等于或忽略首尾空白，不做分词/相似度）。
     * owningSystem 为空时返回空（CLI 无系统上下文场景跳过复用级）。
     * 命中多条且定义不一致时返回全部候选，由决策链标"需人工复核"。
     */
    List<CachedField> findSchemaFields(String owningSystem, String chineseName);

    /** 字典快照：按中文名查字典项（公共集/自治级）。 */
    List<DictItem> findDictItems(String chineseName);

    /**
     * 已确认候选：用户复核通过（LLM 候选/手工修改）的定义，按归属系统+中文名落库。
     * 返回项须由决策链按上下文指纹过滤（指纹不一致表示表单上下文已变，不采用）；
     * 字典快照更新后旧候选由供给方过滤（失效不再返回）。默认空（无确认缓存场景）。
     */
    default List<ConfirmedCandidate> findConfirmed(String owningSystem, String chineseName) {
        return List.of();
    }

    /** 存量字段定义（英文名 + 物理类型照抄，见 ADR-0001）。 */
    record CachedField(String chineseName, String englishName, String physicalType) {
    }

    /**
     * 已确认候选定义：英文名/数据类型/物理类型照抄用户确认结果；
     * contextFingerprint 为确认时的表单上下文指纹（归属系统+表中文名+字段中文名集合）。
     */
    record ConfirmedCandidate(String chineseName, String englishName, String dataType,
                              String physicalType, List<Code> codeList,
                              String contextFingerprint) {
    }

    /**
     * 字典数据项（对齐字典快照导出字段）。
     * formatIllegal 为 true 时表示数据格式/英文名称非法，照常入库、贯标命中标"需人工复核"。
     */
    record DictItem(String id, String chineseName, String englishName, String importance,
                    String dataType, String dataFormat, List<Code> codeList, String codeRule,
                    boolean formatIllegal) {

        public boolean isPublicSet() {
            return "公共集".equals(importance);
        }
    }

    /** 代码清单项："取值 → 名称"键值对。 */
    record Code(String code, String name) {
    }
}
