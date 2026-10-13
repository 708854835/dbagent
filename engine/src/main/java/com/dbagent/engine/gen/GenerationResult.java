package com.dbagent.engine.gen;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * 生成结果：DDL 文本 + 逐字段来源徽章 + 待登记字段清单 + 索引建议占位。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GenerationResult {

    public String tableName;
    public boolean tableNameNeedsReview;
    public String ddl;
    public List<FieldView> fields = new ArrayList<>();
    public List<PendingItem> pendingRegistration = new ArrayList<>();
    public List<IndexSuggestion> indexSuggestions = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FieldView {
        public String chineseName;
        public String englishName;
        public String dataType;
        public String physicalType;
        public String badge;
        public String note;
        /** 代码类完整代码清单（COMMENT 只写摘要，完整清单随结果单独输出） */
        public List<com.dbagent.engine.cache.CacheSupply.Code> codeList;
        /** 复用多命中定义不一致时的全量候选（供用户选择） */
        public List<com.dbagent.engine.cache.CacheSupply.CachedField> candidates;
        /** 锁定字段（增量重生成输入携带 locked=true）：true 时输出，供下次重生成回传 */
        public Boolean locked;
    }

    /** 待登记字段清单项：字典项编号留空不虚构，由数据管理员登记时生成。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PendingItem {
        public String dictItemId = "";
        public String chineseName;
        public String englishName;
        public String importance;
        public String dataType;
        public String dataFormat;
        public String codeList;
    }

    public static class IndexSuggestion {
        public String name;
        public List<String> columns;
        /** 主键索引不参与勾选 */
        public boolean fixed;
        public boolean selected = true;
    }
}
