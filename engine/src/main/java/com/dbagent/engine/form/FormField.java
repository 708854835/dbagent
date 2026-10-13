package com.dbagent.engine.form;

/**
 * 表单字段行：中文名必填，是否必填/是否主键可选，长度/精度仅在推导/LLM 路径生效。
 * 增量重生成：locked=true 时携带用户已确认/已修改的英文定义，引擎照抄不重算。
 */
public class FormField {

    public String chineseName;
    public Boolean required;
    public Boolean primaryKey;
    public Integer length;
    public Integer precision;
    public String comment;
    /** 锁定标记：用户已确认/已手工修改的字段，增量重生成时不动 */
    public Boolean locked;
    /** 锁定字段携带的最终定义（locked=true 时 englishName/physicalType 必填） */
    public String englishName;
    public String dataType;
    public String physicalType;

    public boolean isPrimaryKey() {
        return Boolean.TRUE.equals(primaryKey);
    }

    public boolean isLocked() {
        return Boolean.TRUE.equals(locked);
    }
}
