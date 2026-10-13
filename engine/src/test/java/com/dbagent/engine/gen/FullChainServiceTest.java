package com.dbagent.engine.gen;

import com.dbagent.engine.cache.CacheSupply;
import com.dbagent.engine.cache.ConfirmedService;
import com.dbagent.engine.cache.InMemoryCacheSupply;
import com.dbagent.engine.form.FormField;
import com.dbagent.engine.form.FormInput;
import com.dbagent.engine.rules.EngineConfig;
import com.dbagent.engine.rules.RulesLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则引擎完备化端到端（生成服务缝）：撞名检测、启发式索引建议、
 * 已确认候选缓存复用、增量重生成锁定字段。
 */
class FullChainServiceTest {

    private static EngineConfig config;

    @BeforeAll
    static void setUp() {
        config = RulesLoader.load(Path.of("../config"));
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

    private static CacheSupply.DictItem dictItem(String id, String chineseName,
                                                 String englishName, String importance,
                                                 String dataType, String dataFormat) {
        return new CacheSupply.DictItem(id, chineseName, englishName, importance, dataType,
                dataFormat, List.of(), null, false);
    }

    private static GenerationResult.FieldView field(GenerationResult r, String chineseName) {
        return r.fields.stream().filter(f -> chineseName.equals(f.chineseName))
                .findFirst().orElseThrow();
    }

    // ---------- 撞名检测 ----------

    @Test
    void sameEnglishNameFromDifferentChineseNamesIsMarkedForReview() {
        InMemoryCacheSupply supply = new InMemoryCacheSupply()
                .putDictItem(dictItem("IP1", "客户名称", "cust_name", "公共集", "文本类", "anc..128"))
                .putDictItem(dictItem("IP2", "客户称谓", "cust_name", "自治级", "文本类", "anc..64"));

        GenerationResult r = new GenerationService(config, supply)
                .generate(formWith("客户名称", "客户称谓"));

        assertEquals("需人工复核", field(r, "客户名称").badge);
        assertEquals("需人工复核", field(r, "客户称谓").badge);
        assertTrue(field(r, "客户名称").note.contains("撞名"), field(r, "客户名称").note);
        // 不自动改名、不进 DDL
        assertFalse(r.ddl.contains("cust_name"), r.ddl);
    }

    @Test
    void lockedFieldWinsCollisionAndOtherSideIsMarkedForReview() {
        InMemoryCacheSupply supply = new InMemoryCacheSupply()
                .putDictItem(dictItem("IP1", "客户名称", "cust_name", "公共集", "文本类", "anc..128"));
        FormInput form = formWith("客户名称", "客户称谓");
        FormField locked = form.fields.get(1);
        locked.locked = true;
        locked.englishName = "cust_name";
        locked.dataType = "文本类";
        locked.physicalType = "VARCHAR(64)";

        GenerationResult r = new GenerationService(config, supply).generate(form);
        // 锁定字段不动，照抄进 DDL
        GenerationResult.FieldView lockedView = field(r, "客户称谓");
        assertEquals("复用·本系统", lockedView.badge);
        assertEquals(Boolean.TRUE, lockedView.locked);
        assertTrue(r.ddl.contains("`cust_name` VARCHAR(64)"), r.ddl);
        // 撞锁定的非锁定字段复核，不重复进 DDL
        assertEquals("需人工复核", field(r, "客户名称").badge);
        assertTrue(field(r, "客户名称").note.contains("锁定"), field(r, "客户名称").note);
        assertEquals(1, r.ddl.split("cust_name", -1).length - 1, r.ddl);
    }

    // ---------- 启发式索引建议 ----------

    @Test
    void heuristicIndexSuggestionsCoverCodeAndReferenceColumns() {
        InMemoryCacheSupply supply = new InMemoryCacheSupply()
                .putDictItem(dictItem("IP1", "币种", "ccy", "公共集", "代码类", "an3"));

        GenerationResult r = new GenerationService(config, supply)
                .generate(formWith("合同编号", "币种", "机构编号", "借款金额", "备注"));

        assertEquals("PRIMARY", r.indexSuggestions.get(0).name);
        assertTrue(r.indexSuggestions.get(0).fixed);
        List<String> suggested = r.indexSuggestions.stream().skip(1).map(i -> i.name).toList();
        // 编码类（contract_no/org_no）+ 代码类（ccy）入选；金额类/文本类不入选
        assertEquals(List.of("idx_contract_no", "idx_ccy", "idx_org_no"), suggested);
        assertTrue(r.indexSuggestions.stream().skip(1).allMatch(i -> !i.fixed));
    }

    @Test
    void heuristicIndexSuggestionsAreCappedAtFive() {
        GenerationResult r = new GenerationService(config, new InMemoryCacheSupply())
                .generate(formWith("合同编号", "客户编号", "机构编号", "产品编号",
                        "借据编号", "银行编号", "备注"));

        long heuristic = r.indexSuggestions.stream().filter(i -> !i.fixed).count();
        assertEquals(5, heuristic, "启发式候选列数量应 ≤5");
    }

    // ---------- 已确认候选缓存 ----------

    @Test
    void confirmedCandidateIsReusedWhenFingerprintMatches() {
        String system = "loan-system";
        List<String> fieldNames = List.of("客户编号", "借款金额");
        String fingerprint = ConfirmedService.fingerprint(system, "同业借款合同台账", fieldNames);
        InMemoryCacheSupply supply = new InMemoryCacheSupply()
                .putConfirmed(system, new CacheSupply.ConfirmedCandidate(
                        "客户编号", "cust_no", "编码类", "VARCHAR(20)", null, fingerprint));

        FormInput form = formWith("客户编号", "借款金额");
        form.owningSystem = system;
        GenerationResult r = new GenerationService(config, supply).generate(form);

        GenerationResult.FieldView view = field(r, "客户编号");
        assertEquals("复用·本系统", view.badge);
        assertEquals("cust_no", view.englishName);
        assertEquals("VARCHAR(20)", view.physicalType);
        assertTrue(view.note.contains("已确认"), view.note);
        assertTrue(r.ddl.contains("`cust_no` VARCHAR(20)"), r.ddl);
    }

    @Test
    void confirmedCandidateIsIgnoredWhenContextChanged() {
        String system = "loan-system";
        // 确认时的表单只有 2 个字段；本次生成多了 1 个 → 指纹不一致
        String fingerprint = ConfirmedService.fingerprint(system, "同业借款合同台账",
                List.of("客户编号", "借款金额"));
        InMemoryCacheSupply supply = new InMemoryCacheSupply()
                .putConfirmed(system, new CacheSupply.ConfirmedCandidate(
                        "客户编号", "cust_no", "编码类", "VARCHAR(20)", null, fingerprint));

        FormInput form = formWith("客户编号", "借款金额", "借款日期");
        form.owningSystem = system;
        GenerationResult r = new GenerationService(config, supply).generate(form);

        // 上下文已变：确认缓存不采用，落入推导路径（cust_no 徽章推导·待登记）
        GenerationResult.FieldView view = field(r, "客户编号");
        assertEquals("推导·待登记", view.badge);
        assertEquals("cust_no", view.englishName);
    }

    // ---------- 增量重生成 ----------

    @Test
    void lockedFieldsAreCopiedUnchangedAndOthersRecomputed() {
        FormInput form = formWith("合同编号", "特殊目的载体");
        FormField locked = form.fields.get(1);
        locked.locked = true;
        locked.englishName = "spv_ref";
        locked.dataType = "文本类";
        locked.physicalType = "VARCHAR(64)";

        GenerationResult r = new GenerationService(config, new InMemoryCacheSupply())
                .generate(form);

        // "特殊目的载体"词典未覆盖、未锁定时必为复核；锁定后照抄进 DDL
        GenerationResult.FieldView lockedView = field(r, "特殊目的载体");
        assertEquals("复用·本系统", lockedView.badge);
        assertEquals(Boolean.TRUE, lockedView.locked);
        assertEquals("spv_ref", lockedView.englishName);
        assertTrue(r.ddl.contains("`spv_ref` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '特殊目的载体'"),
                r.ddl);
        // 其余字段照常重算
        assertEquals("推导·待登记", field(r, "合同编号").badge);
        assertNull(field(r, "合同编号").locked);
        assertTrue(r.warnings.isEmpty(), String.join(";", r.warnings));
    }

    @Test
    void lockedFieldWithIllegalEnglishNameIsMarkedForReview() {
        FormInput form = formWith("合同编号", "特殊目的载体");
        FormField locked = form.fields.get(1);
        locked.locked = true;
        locked.englishName = "1_bad name";
        locked.dataType = "文本类";
        locked.physicalType = "VARCHAR(64)";

        GenerationResult r = new GenerationService(config, new InMemoryCacheSupply())
                .generate(form);
        assertEquals("需人工复核", field(r, "特殊目的载体").badge);
        assertFalse(r.ddl.contains("1_bad"), r.ddl);
    }
}
