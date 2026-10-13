package com.dbagent.engine.form;

import java.util.ArrayList;
import java.util.List;

/**
 * 建表向导表单录入（与 HTTP 请求体同构；CLI 直读此 JSON）。
 * owningSystem 为本系统标识（复用缓存按系统隔离）；HTTP 场景由 Token 上下文注入。
 */
public class FormInput {

    public String tableChineseName;
    public String description;
    public String notesId;
    public String owningSystem;
    public List<FormField> fields = new ArrayList<>();
}
