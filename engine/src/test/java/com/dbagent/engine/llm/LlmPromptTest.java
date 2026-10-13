package com.dbagent.engine.llm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LLM 提示词约束：强制"释义+理由"输出，候选 ≤3、置信度 0-1、表内已定名避重。
 */
class LlmPromptTest {

    @Test
    void promptCarriesTableFieldAndExistingNames() {
        String prompt = LlmPrompt.build(new LlmNamingProvider.FieldContext(
                "同业借款合同台账", "特殊目的载体", List.of("contract_no", "remark")));
        assertTrue(prompt.contains("同业借款合同台账"), prompt);
        assertTrue(prompt.contains("特殊目的载体"), prompt);
        assertTrue(prompt.contains("contract_no"), prompt);
        assertTrue(prompt.contains("remark"), prompt);
    }

    @Test
    void promptEnforcesRationaleConfidenceAndCandidateCap() {
        String prompt = LlmPrompt.build(new LlmNamingProvider.FieldContext(
                "台账", "字段甲", List.of()));
        // 强制释义+理由、候选 ≤3、置信度 0-1
        assertTrue(prompt.contains("释义与理由"), prompt);
        assertTrue(prompt.contains("3 个候选"), prompt);
        assertTrue(prompt.contains("0-1"), prompt);
        // 无已定名时显式标注，避免模型误解
        assertTrue(prompt.contains("（无）"), prompt);
    }

    @Test
    void tablePromptCarriesNameDescriptionAndNamingConvention() {
        String prompt = LlmPrompt.buildTableName(new LlmNamingProvider.TableContext(
                "特殊目的载体资产池", "同业 SPV 资产池登记"));
        assertTrue(prompt.contains("特殊目的载体资产池"), prompt);
        assertTrue(prompt.contains("同业 SPV 资产池登记"), prompt);
        // 表名约定：小写连写不加下划线、不含 t_ 前缀（系统统一加）
        assertTrue(prompt.contains("不加下划线"), prompt);
        assertTrue(prompt.contains("不含 t_ 前缀"), prompt);
        assertTrue(prompt.contains("3 个候选"), prompt);
        assertTrue(prompt.contains("释义与理由"), prompt);
        assertTrue(prompt.contains("0-1"), prompt);
    }

    @Test
    void tablePromptMarksBlankDescription() {
        String prompt = LlmPrompt.buildTableName(new LlmNamingProvider.TableContext("台账", " "));
        assertTrue(prompt.contains("（无）"), prompt);
    }
}
