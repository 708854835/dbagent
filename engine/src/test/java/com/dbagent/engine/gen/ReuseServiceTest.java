package com.dbagent.engine.gen;

import com.dbagent.engine.cache.CacheSupply;
import com.dbagent.engine.cache.InMemoryCacheSupply;
import com.dbagent.engine.form.FormField;
import com.dbagent.engine.form.FormInput;
import com.dbagent.engine.rules.EngineConfig;
import com.dbagent.engine.rules.RulesLoader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成服务缝：复用路径端到端（ADR-0001 照抄存量、多命中候选、>80% 提示）。
 */
class ReuseServiceTest {

    private static final String SYSTEM = "loan-system";

    private static EngineConfig config;

    @BeforeAll
    static void setUp() {
        config = RulesLoader.load(Path.of("../config"));
    }

    @Test
    void singleHitReusesExistingDefinitionVerbatim() {
        InMemoryCacheSupply cache = new InMemoryCacheSupply()
                .putSchemaField(SYSTEM,
                        new CacheSupply.CachedField("客户编号", "Cust_No", "VARCHAR(64)"));

        GenerationResult r = service(cache).generate(formWith("客户编号", 32));

        GenerationResult.FieldView view = r.fields.get(0);
        assertEquals("复用·本系统", view.badge);
        // 照抄存量（输出统一小写），忽略手填长度 32、不读类型映射
        assertEquals("cust_no", view.englishName);
        assertEquals("VARCHAR(64)", view.physicalType);
        // NOT NULL/默认值/comment 按规则补齐
        assertTrue(r.ddl.contains("`cust_no` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '客户编号'"),
                r.ddl);
        // 复用不进入待登记清单
        assertTrue(r.pendingRegistration.isEmpty());
    }

    @Test
    void multipleIdenticalHitsAreAdopted() {
        InMemoryCacheSupply cache = new InMemoryCacheSupply()
                .putSchemaField(SYSTEM, new CacheSupply.CachedField("客户名称", "cust_name", "VARCHAR(128)"))
                .putSchemaField(SYSTEM, new CacheSupply.CachedField("客户名称", "cust_name", "VARCHAR(128)"));

        GenerationResult r = service(cache).generate(formWith("客户名称", null));
        assertEquals("复用·本系统", r.fields.get(0).badge);
        assertEquals("cust_name", r.fields.get(0).englishName);
    }

    @Test
    void conflictingHitsListAllCandidatesForReview() {
        InMemoryCacheSupply cache = new InMemoryCacheSupply()
                .putSchemaField(SYSTEM, new CacheSupply.CachedField("备注", "remark", "VARCHAR(128)"))
                .putSchemaField(SYSTEM, new CacheSupply.CachedField("备注", "memo", "VARCHAR(256)"))
                // 重复定义应去重
                .putSchemaField(SYSTEM, new CacheSupply.CachedField("备注", "remark", "VARCHAR(128)"));

        GenerationResult r = service(cache).generate(formWith("备注", null));

        GenerationResult.FieldView view = r.fields.get(0);
        assertEquals("需人工复核", view.badge);
        assertEquals(2, view.candidates.size());
        assertEquals("remark", view.candidates.get(0).englishName());
        assertEquals("memo", view.candidates.get(1).englishName());
        // 复核字段不进入 DDL
        assertFalse(r.ddl.contains("COMMENT '备注'"), r.ddl);
    }

    @Test
    void reuseRatioOver80PercentHintsContextTuning() {
        InMemoryCacheSupply cache = new InMemoryCacheSupply()
                .putSchemaField(SYSTEM, new CacheSupply.CachedField("客户编号", "cust_no", "VARCHAR(64)"))
                .putSchemaField(SYSTEM, new CacheSupply.CachedField("客户名称", "cust_name", "VARCHAR(128)"))
                .putSchemaField(SYSTEM, new CacheSupply.CachedField("借款金额", "loan_amt", "DECIMAL(18,2)"))
                .putSchemaField(SYSTEM, new CacheSupply.CachedField("借款日期", "loan_date", "DATE"))
                .putSchemaField(SYSTEM, new CacheSupply.CachedField("还款日期", "repay_date", "DATE"));

        FormInput form = new FormInput();
        form.tableChineseName = "同业借款合同台账";
        form.owningSystem = SYSTEM;
        addField(form, "客户编号");
        addField(form, "客户名称");
        addField(form, "借款金额");
        addField(form, "借款日期");
        addField(form, "还款日期");
        addField(form, "合同编号"); // 唯一走推导的字段

        GenerationResult r = service(cache).generate(form);
        assertEquals(5, r.fields.stream().filter(f -> "复用·本系统".equals(f.badge)).count());
        assertTrue(r.warnings.stream().anyMatch(w -> w.contains(">80%") && w.contains("结合上下文微调")),
                r.warnings.toString());
    }

    @Test
    void blankOwningSystemSkipsReuseTier() {
        InMemoryCacheSupply cache = new InMemoryCacheSupply()
                .putSchemaField(SYSTEM, new CacheSupply.CachedField("客户编号", "cust_no", "VARCHAR(64)"));

        FormInput form = formWith("客户编号", null);
        form.owningSystem = null; // CLI 无系统上下文场景
        GenerationResult r = service(cache).generate(form);
        assertEquals("推导·待登记", r.fields.get(0).badge);
    }

    private GenerationService service(InMemoryCacheSupply cache) {
        return new GenerationService(config, cache);
    }

    private static FormInput formWith(String chineseName, Integer length) {
        FormInput form = new FormInput();
        form.tableChineseName = "同业借款合同台账";
        form.owningSystem = SYSTEM;
        FormField f = new FormField();
        f.chineseName = chineseName;
        f.length = length;
        form.fields.add(f);
        return form;
    }

    private static void addField(FormInput form, String chineseName) {
        FormField f = new FormField();
        f.chineseName = chineseName;
        form.fields.add(f);
    }
}
