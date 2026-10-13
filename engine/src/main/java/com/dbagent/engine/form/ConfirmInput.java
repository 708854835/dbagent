package com.dbagent.engine.form;

import java.util.ArrayList;
import java.util.List;

/**
 * 已确认候选登记输入（CLI confirm 子命令 / HTTP POST /confirm 共用）：
 * 用户复核通过的 LLM 候选/手工修改结果回传，按 系统+中文名+上下文指纹 落库。
 * fieldNames 为确认时表单的完整字段中文名集合（与表中文名一起构成上下文指纹）。
 */
public class ConfirmInput {

    public String tableChineseName;
    /** 归属系统；HTTP 场景由 Token 上下文注入（覆盖自称身份） */
    public String owningSystem;
    public List<String> fieldNames = new ArrayList<>();
    public List<ConfirmedField> confirmed = new ArrayList<>();

    /** 单个复核通过的字段定义（英文名/数据类型/物理类型为最终确认值）。 */
    public static class ConfirmedField {
        public String chineseName;
        public String englishName;
        public String dataType;
        public String physicalType;
        public List<com.dbagent.engine.cache.CacheSupply.Code> codeList;
    }
}
