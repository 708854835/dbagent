package com.dbagent.engine.gen;

import com.dbagent.engine.cache.InMemoryCacheSupply;
import com.dbagent.engine.form.FormField;
import com.dbagent.engine.form.FormInput;
import com.dbagent.engine.llm.LlmAssistService;
import com.dbagent.engine.llm.StubLlmNamingProvider;
import com.dbagent.engine.rules.EngineConfig;
import com.dbagent.engine.rules.LlmRules;
import com.dbagent.engine.rules.RulesLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成服务缝 + LLM 辅助端到端：词典未覆盖词走桩 LLM，
 * 唯一高置信采纳（LLM·候选）/非唯一/超时/重名降级，绝不静默采用。
 */
class GenerationServiceLlmTest {

    private static EngineConfig config;

    @BeforeAll
    static void setUp() {
        config = RulesLoader.load(Path.of("../config"));
    }

    private static LlmRules llmRules() {
        LlmRules r = new LlmRules();
        r.enabled = true;
        r.maxConcurrentColumns = 5;
        r.singleColumnTimeoutSeconds = 3;
        r.overallTimeoutSeconds = 5;
        r.minConfidence = 0.8;
        r.maxCandidates = 3;
        return r;
    }

    private static LlmRules.StubCandidate cand(String name, double confidence, String rationale) {
        LlmRules.StubCandidate c = new LlmRules.StubCandidate();
        c.englishName = name;
        c.confidence = confidence;
        c.rationale = rationale;
        return c;
    }

    private static GenerationService serviceWith(LlmRules rules) {
        return new GenerationService(config, new InMemoryCacheSupply(),
                new LlmAssistService(new StubLlmNamingProvider(rules.stub), rules));
    }

    private static FormInput formWith(String... chineseNames) {
        FormInput form = new FormInput();
        form.tableChineseName = "同业借款合同台账";
        for (String name : chineseNames) {
            FormField f = new FormField();
            f.chineseName = name;
            form.fields.add(f);
        }
        return form;
    }

    private static GenerationResult.FieldView field(GenerationResult r, String chineseName) {
        return r.fields.stream().filter(f -> chineseName.equals(f.chineseName))
                .findFirst().orElseThrow();
    }

    @Test
    void uniqueHighConfidenceCandidateIsAdoptedAsLlmBadge() {
        LlmRules rules = llmRules();
        rules.stub.candidates = Map.of("特殊目的载体",
                List.of(cand("spv", 0.92, "Special Purpose Vehicle 业界通用缩写")));
        FormInput form = formWith("合同编号", "特殊目的载体");
        form.fields.get(1).length = 64;

        GenerationResult r = serviceWith(rules).generate(form);
        GenerationResult.FieldView view = field(r, "特殊目的载体");
        assertEquals("LLM·候选", view.badge);
        assertEquals("spv", view.englishName);
        // 数据类型按结尾类词推断（无命中 → 兜底文本类），手填长度在 LLM 路径生效
        assertEquals("文本类", view.dataType);
        assertEquals("VARCHAR(64)", view.physicalType);
        assertTrue(view.note.contains("0.92"), view.note);
        assertTrue(view.note.contains("Special Purpose Vehicle"), view.note);
        // 采纳字段进入 DDL
        assertTrue(r.ddl.contains("`spv` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '特殊目的载体'"),
                r.ddl);
        // 待登记清单：编号占位 IP00000000
        GenerationResult.PendingItem item = r.pendingRegistration.stream()
                .filter(p -> "特殊目的载体".equals(p.chineseName)).findFirst().orElseThrow();
        assertEquals("IP00000000", item.dictItemId);
        assertEquals("spv", item.englishName);
        assertEquals("anc..64", item.dataFormat);
        assertTrue(r.warnings.stream().noneMatch(w -> w.contains("特殊目的载体")));
    }

    @Test
    void nonUniqueHighConfidenceDegradesWithCandidatesListed() {
        LlmRules rules = llmRules();
        rules.stub.candidates = Map.of("受托机构", List.of(
                cand("trustee_org", 0.85, "受托 trustee"),
                cand("entrust_org", 0.83, "受托 entrust")));

        GenerationResult r = serviceWith(rules).generate(formWith("合同编号", "受托机构"));
        GenerationResult.FieldView view = field(r, "受托机构");
        assertEquals("需人工复核", view.badge);
        assertTrue(view.note.contains("非唯一"), view.note);
        // 候选保留供人工参考
        assertEquals(2, view.llmCandidates.size());
        assertEquals("trustee_org", view.llmCandidates.get(0).englishName());
        // 未采纳不进 DDL、不进待登记
        assertFalse(r.ddl.contains("trustee_org"), r.ddl);
        assertTrue(r.pendingRegistration.stream()
                .noneMatch(p -> "受托机构".equals(p.chineseName)));
        assertTrue(r.warnings.stream().anyMatch(w -> w.contains("受托机构")));
    }

    @Test
    void timeoutDegradesWithoutBlockingGeneration() {
        LlmRules rules = llmRules();
        rules.singleColumnTimeoutSeconds = 1;
        rules.stub.slowNames = List.of("估值基准日");
        rules.stub.slowLatencyMillis = 6_000;
        rules.stub.candidates = Map.of("估值基准日",
                List.of(cand("val_base_date", 0.95, "估值基准日直译")));

        GenerationResult r = serviceWith(rules).generate(formWith("合同编号", "估值基准日"));
        GenerationResult.FieldView view = field(r, "估值基准日");
        assertEquals("需人工复核", view.badge);
        assertTrue(view.note.contains("超时"), view.note);
        // 其他字段不受影响，DDL 正常生成
        assertTrue(r.ddl.contains("`contract_no`"), r.ddl);
        assertFalse(r.ddl.contains("val_base_date"), r.ddl);
    }

    @Test
    void adoptedNameConflictingWithExistingColumnDegrades() {
        LlmRules rules = llmRules();
        // "备注" 推导为 remark；LLM 对未覆盖词也给出 remark（高置信）→ 表内重名必须降级
        rules.stub.candidates = Map.of("特殊目的载体",
                List.of(cand("remark", 0.95, "伪理由")));

        GenerationResult r = serviceWith(rules)
                .generate(formWith("备注", "特殊目的载体"));
        GenerationResult.FieldView view = field(r, "特殊目的载体");
        assertEquals("需人工复核", view.badge);
        assertTrue(view.note.contains("重名"), view.note);
        // 重名候选未进入 DDL（remark 仅出现一次，归属"备注"字段）
        assertTrue(r.ddl.contains("`remark` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '备注'"), r.ddl);
        assertTrue(r.pendingRegistration.stream()
                .noneMatch(p -> "特殊目的载体".equals(p.chineseName)));
    }

    @Test
    void llmDisabledKeepsPlainReviewWithoutCandidates() {
        // 未注入 LLM 编排（enabled=false 时的运行形态）
        GenerationService service = new GenerationService(config, new InMemoryCacheSupply());
        GenerationResult r = service.generate(formWith("合同编号", "特殊目的载体"));
        GenerationResult.FieldView view = field(r, "特殊目的载体");
        assertEquals("需人工复核", view.badge);
        assertTrue(view.note.contains("缩写词典未覆盖"), view.note);
        assertNull(view.llmCandidates);
    }
}
