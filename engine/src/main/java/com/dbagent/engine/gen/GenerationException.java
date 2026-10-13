package com.dbagent.engine.gen;

/**
 * 生成失败的业务异常（阻断级校验、字段数超限等），携带错误码。
 */
public final class GenerationException extends RuntimeException {

    private final String code;

    public GenerationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
