package com.dbagent.engine.gen;

import java.util.List;

/**
 * 生成失败的业务异常（阻断级校验、字段数超限等），携带错误码。
 * details 列出具体违规字段/原因（HTTP 错误信封 details 用），可为空。
 */
public final class GenerationException extends RuntimeException {

    private final String code;
    private final List<String> details;

    public GenerationException(String code, String message) {
        this(code, message, List.of());
    }

    public GenerationException(String code, String message, List<String> details) {
        super(message);
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public String code() {
        return code;
    }

    /** 具体违规清单（如"第 3 个字段中文名为空"）；无细节时为空清单 */
    public List<String> details() {
        return details;
    }
}
