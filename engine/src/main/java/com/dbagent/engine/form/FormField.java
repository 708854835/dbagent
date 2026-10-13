package com.dbagent.engine.form;

/**
 * 表单字段行：中文名必填，是否必填/是否主键可选，长度/精度仅在推导/LLM 路径生效。
 */
public class FormField {

    public String chineseName;
    public Boolean required;
    public Boolean primaryKey;
    public Integer length;
    public Integer precision;
    public String comment;

    public boolean isPrimaryKey() {
        return Boolean.TRUE.equals(primaryKey);
    }
}
