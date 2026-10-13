package com.dbagent.engine.form;

import java.util.ArrayList;
import java.util.List;

/**
 * 建表向导表单录入（与 HTTP 请求体同构；CLI 直读此 JSON）。
 */
public class FormInput {

    public String tableChineseName;
    public String description;
    public String notesId;
    public List<FormField> fields = new ArrayList<>();
}
