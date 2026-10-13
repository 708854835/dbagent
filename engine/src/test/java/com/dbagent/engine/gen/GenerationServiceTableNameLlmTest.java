package com.dbagent.engine.gen;

import com.dbagent.engine.cache.InMemoryCacheSupply;
import com.dbagent.engine.form.FormField;
import com.dbagent.engine.form.FormInput;
import com.dbagent.engine.llm.LlmAssistService;
import com.dbagent.engine.llm.LlmNamingProvider;
import com.dbagent.engine.llm.StubLlmNamingProvider;
import com.dbagent.engine.rules.EngineConfig;
import com.dbagent.engine.rules.LlmRules;
import com.dbagent.engine.rules.RulesLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 表名 LLM 辅助端到端：表名缩写词典未覆盖词 → LLM 候选表名 →
 * 小写/合法字符/长度校验通过且唯一高置信才采纳；不过则表名人工复核（候选留存参考）。
 * 词典覆盖时绝不触发 LLM（规则能决断不调用）。
 */
class GenerationServiceTableNameLlmTest {

    /** 表名含词典未覆盖词（特殊/目的/载体/池），触发 LLM 表名辅助 */
    private static final String UNCOVERED_TABLE = "特殊目的载体资产池";

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

    private static FormInput formWithTable(String tableChineseName) {
        FormInput form = new FormInput();
        form.tableChineseName = tableChineseName;
        FormField f = new FormField();
        f.chineseName = "合同编号";
        form.fields.add(f);
        return form;
    }

    @Test
    void uniqueHighConfidenceValidCandidateIsAdopted() {
        LlmRules rules = llmRules();
        rules.stub.tableCandidates = Map.of(UNCOVERED_TABLE,
                List.of(cand("spvpool", 0.91, "特殊目的载体业界缩写 SPV + 池 pool")));

        GenerationResult r = serviceWith(rules).generate(formWithTable(UNCOVERED_TABLE));
        assertEquals("t_spvpool", r.tableName);
        assertFalse(r.tableNameNeedsReview);
        assertEquals(1, r.tableNameLlmCandidates.size());
        assertTrue(r.ddl.contains("`t_spvpool`"), r.ddl);
        assertTrue(r.warnings.stream().anyMatch(
                w -> w.contains("采用 LLM 建议表名") && w.contains("0.91")), r.warnings.toString());
    }

    @Test
    void candidateWithPrefixIsStrippedAndAdopted() {
        LlmRules rules = llmRules();
        rules.stub.tableCandidates = Map.of(UNCOVERED_TABLE,
                List.of(cand("t_spvpool", 0.9, "带前缀候选（系统剥离前缀后校验通过）")));

        GenerationResult r = serviceWith(rules).generate(formWithTable(UNCOVERED_TABLE));
        assertEquals("t_spvpool", r.tableName);
        assertFalse(r.tableNameNeedsReview);
    }

    @Test
    void illegalCharsCandidateDegradesToTableNameReview() {
        LlmRules rules = llmRules();
        // 表名简称连写不加下划线：带下划线的候选必须降级
        rules.stub.tableCandidates = Map.of(UNCOVERED_TABLE,
                List.of(cand("spv_pool", 0.95, "伪理由")));

        GenerationResult r = serviceWith(rules).generate(formWithTable(UNCOVERED_TABLE));
        assertTrue(r.tableNameNeedsReview);
        assertFalse("t_spv_pool".equals(r.tableName), r.tableName);
        assertTrue(r.warnings.stream().anyMatch(
                w -> w.contains("spv_pool") && w.contains("非法字符")), r.warnings.toString());
        // 候选留存供人工参考
        assertEquals(1, r.tableNameLlmCandidates.size());
        assertEquals("spv_pool", r.tableNameLlmCandidates.get(0).englishName());
    }

    @Test
    void tooLongCandidateDegradesToTableNameReview() {
        LlmRules rules = llmRules();
        rules.stub.tableCandidates = Map.of(UNCOVERED_TABLE,
                List.of(cand("a".repeat(70), 0.95, "伪理由")));

        GenerationResult r = serviceWith(rules).generate(formWithTable(UNCOVERED_TABLE));
        assertTrue(r.tableNameNeedsReview);
        assertTrue(r.warnings.stream().anyMatch(w -> w.contains("字节上限")), r.warnings.toString());
    }

    @Test
    void nonUniqueHighConfidenceDegradesWithCandidatesListed() {
        LlmRules rules = llmRules();
        rules.stub.tableCandidates = Map.of(UNCOVERED_TABLE, List.of(
                cand("spvpool", 0.85, "译法一"),
                cand("spvasset", 0.83, "译法二")));

        GenerationResult r = serviceWith(rules).generate(formWithTable(UNCOVERED_TABLE));
        assertTrue(r.tableNameNeedsReview);
        assertTrue(r.warnings.stream().anyMatch(
                w -> w.contains("未给出可采纳候选") && w.contains("非唯一")), r.warnings.toString());
        assertEquals(2, r.tableNameLlmCandidates.size());
    }

    @Test
    void llmDisabledKeepsPlaceholderReviewWithoutCandidates() {
        GenerationService service = new GenerationService(config, new InMemoryCacheSupply());
        GenerationResult r = service.generate(formWithTable(UNCOVERED_TABLE));
        assertTrue(r.tableNameNeedsReview);
        assertNull(r.tableNameLlmCandidates);
        assertTrue(r.warnings.stream().anyMatch(w -> w.contains("表名需人工复核")), r.warnings.toString());
    }

    @Test
    void coveredTableNameNeverTriggersLlm() {
        AtomicInteger tableCalls = new AtomicInteger();
        LlmNamingProvider provider = new LlmNamingProvider() {
            @Override
            public List<Candidate> suggest(FieldContext context) {
                return List.of();
            }

            @Override
            public List<Candidate> suggestTableName(TableContext context) {
                tableCalls.incrementAndGet();
                return List.of();
            }
        };
        GenerationService service = new GenerationService(config, new InMemoryCacheSupply(),
                new LlmAssistService(provider, llmRules()));

        GenerationResult r = service.generate(formWithTable("同业借款合同台账"));
        assertEquals("t_tyjkht", r.tableName);
        assertFalse(r.tableNameNeedsReview);
        assertNull(r.tableNameLlmCandidates);
        assertEquals(0, tableCalls.get(), "词典覆盖时不得触发 LLM 表名辅助");
    }
}
