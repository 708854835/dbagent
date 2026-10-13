package com.dbagent.engine.llm;

import java.util.stream.Collectors;

/**
 * LLM 提示词构建：强制模型输出"释义+理由"，候选 ≤3 个、置信度 0-1。
 * 单独成类便于单测断言提示词约束不随实现漂移。
 */
public final class LlmPrompt {

    private LlmPrompt() {
    }

    public static String build(LlmNamingProvider.FieldContext context) {
        String existing = context.existingNames() == null || context.existingNames().isEmpty()
                ? "（无）"
                : context.existingNames().stream().collect(Collectors.joining(", "));
        return """
                你是银行数据建模命名助手。请为数据库字段建议英文名。

                表中文名：%s
                字段中文名：%s
                表内已定英文名（不得重名）：%s

                要求：
                1. 最多给出 3 个候选英文名，小写蛇形（snake_case），尽量使用银行业通用缩写；
                2. 每个候选必须给出置信度（0-1 之间的小数）；
                3. 每个候选必须给出释义与理由（为什么这样译、缩写来源），不给理由的候选无效；
                4. 不确定时宁可使置信度偏低，也不要编造。
                输出 JSON 数组：[{"englishName":"...","confidence":0.0,"rationale":"..."}]
                """.formatted(context.tableChineseName(), context.chineseName(), existing);
    }

    public static String buildTableName(LlmNamingProvider.TableContext context) {
        String description = context.description() == null || context.description().isBlank()
                ? "（无）" : context.description().trim();
        return """
                你是银行数据建模命名助手。请为数据库表建议英文名。

                表中文名：%s
                表用途描述：%s

                要求：
                1. 最多给出 3 个候选表名，小写字母连写（不加下划线、不含 t_ 前缀，前缀由系统统一添加）；
                2. 尽量使用银行业通用缩写（如 acct/cust/txn），整体尽量不超过 24 字节；
                3. 每个候选必须给出置信度（0-1 之间的小数）；
                4. 每个候选必须给出释义与理由（为什么这样译、缩写来源），不给理由的候选无效；
                5. 不确定时宁可使置信度偏低，也不要编造。
                输出 JSON 数组：[{"englishName":"...","confidence":0.0,"rationale":"..."}]
                """.formatted(context.tableChineseName(), description);
    }
}
