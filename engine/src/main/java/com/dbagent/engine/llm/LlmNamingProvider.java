package com.dbagent.engine.llm;

import java.util.List;

/**
 * LLM 命名辅助缝：词典未覆盖词 → 英文名候选（≤3 个，含置信度与理由）。
 * 实现可以是真实 LLM，也可以是演示/测试桩；超时与异常由编排层（LlmAssistService）兜底。
 * suggestTableName/normalizeComment 为 default 方法：保持接口函数式（测试可 lambda 注入），
 * 不支持的实现返回空候选/抛异常，编排层据此降级。
 */
public interface LlmNamingProvider {

    /**
     * @return 候选清单（实现可返回任意数量，编排层截取前 N 个）；永不返回 null
     * @throws Exception 调用失败/被中断时抛出，编排层降级为"需人工复核"
     */
    List<Candidate> suggest(FieldContext context) throws Exception;

    /**
     * 表名辅助：表名缩写词典未覆盖词 → 表名候选（不含 t_ 前缀，小写连写不加下划线）。
     * 默认返回空候选（降级表名人工复核）。
     */
    default List<Candidate> suggestTableName(TableContext context) throws Exception {
        return List.of();
    }

    /**
     * 离线 COMMENT 规范化兜底：脏 COMMENT → 标准 JSON 字符串
     * （{"name":"业务中文名","codes":{"01":"男"}}，codes 可省）。
     * 默认抛异常表示不支持，调用方按"LLM 无法规范化"处理。
     */
    default String normalizeComment(String rawComment) throws Exception {
        throw new UnsupportedOperationException("该实现不支持 COMMENT 规范化");
    }

    /**
     * @param englishName 建议英文名（小写蛇形；表名候选为小写连写）
     * @param confidence  置信度 0-1
     * @param rationale   释义与理由（必须给出，供人工判断）
     */
    record Candidate(String englishName, double confidence, String rationale) {
    }

    /**
     * @param tableChineseName 所属表中文名（上下文）
     * @param chineseName      字段中文名
     * @param existingNames    表内已定英文名（避免重名建议）
     */
    record FieldContext(String tableChineseName, String chineseName, List<String> existingNames) {
    }

    /**
     * @param tableChineseName 表中文名
     * @param description      表用途描述（可为 null，辅助判断语义）
     */
    record TableContext(String tableChineseName, String description) {
    }
}
