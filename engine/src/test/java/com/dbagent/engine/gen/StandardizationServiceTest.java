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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成服务缝：贯标路径端到端（公共集优先/存量兼容小写/代码类清单/非法条目复核）。
 */
class StandardizationServiceTest {

    private static EngineConfig config;

    @BeforeAll
    static void setUp() {
        config = RulesLoader.load(Path.of("../config"));
    }

    @Test
    void publicSetWinsOverAutonomousAndCodeClassHandled() {
        InMemoryCacheSupply cache = new InMemoryCacheSupply()
                .putDictItem(new CacheSupply.DictItem("IP00000002", "币种", "currency_type",
                        "自治级", "代码类", "an3", List.of(), null, false))
                .putDictItem(new CacheSupply.DictItem("IP00000001", "币种", "ccy",
                        "公共集", "代码类", "an3",
                        List.of(
                                new CacheSupply.Code("CNY", "人民币"),
                                new CacheSupply.Code("USD", "美元"),
                                new CacheSupply.Code("EUR", "欧元"),
                                new CacheSupply.Code("JPY", "日元"),
                                new CacheSupply.Code("HKD", "港币"),
                                new CacheSupply.Code("GBP", "英镑")),
                        "ISO 4217 3 位字母编码", false));

        GenerationResult r = service(cache).generate(formWith("币种"));

        GenerationResult.FieldView ccy = r.fields.get(0);
        // 公共集优先于自治级
        assertEquals("贯标·字典", ccy.badge);
        assertEquals("ccy", ccy.englishName);
        assertEquals("代码类", ccy.dataType);
        // 长度按取值设计（最大取值 3），文本存储禁浮点
        assertEquals("VARCHAR(3)", ccy.physicalType);
        // 完整代码清单随结果单独输出
        assertEquals(6, ccy.codeList.size());
        // COMMENT 只写前 5 项 + "等共 N 项"摘要
        assertTrue(r.ddl.contains("COMMENT '币种（CNY=人民币，USD=美元，EUR=欧元，JPY=日元，HKD=港币，等共 6 项）'"),
                r.ddl);
        // 贯标命中不进入待登记清单
        assertTrue(r.pendingRegistration.isEmpty());
    }

    @Test
    void legacyPublicSetNameIsAdoptedAndLowercased() {
        InMemoryCacheSupply cache = new InMemoryCacheSupply()
                .putDictItem(new CacheSupply.DictItem("IP00000003", "客户名称", "Cust_Name",
                        "公共集", "文本类", "anc..128", List.of(), null, false));

        GenerationResult r = service(cache).generate(formWith("客户名称"));

        // 存量公共集兼容采用：选词与下划线结构照抄，输出统一小写
        assertEquals("cust_name", r.fields.get(0).englishName);
        assertEquals("VARCHAR(128)", r.fields.get(0).physicalType);
        assertTrue(r.ddl.contains("`cust_name` VARCHAR(128) NOT NULL DEFAULT '' COMMENT '客户名称'"), r.ddl);
    }

    @Test
    void illegalFormatItemIsMarkedForReview() {
        InMemoryCacheSupply cache = new InMemoryCacheSupply()
                .putDictItem(new CacheSupply.DictItem("IP00000005", "交易日期", "txn_date",
                        "公共集", "日期类", "YYYY-MM-DD", List.of(), null, true));

        GenerationResult r = service(cache).generate(formWith("交易日期"));

        GenerationResult.FieldView view = r.fields.get(0);
        assertEquals("需人工复核", view.badge);
        assertTrue(view.note.contains("IP00000005"));
        // 复核字段不进入 DDL
        assertFalse(r.ddl.contains("txn_date"), r.ddl);
        assertFalse(r.warnings.isEmpty());
    }

    @Test
    void userLengthIsIgnoredOnStandardizedHit() {
        InMemoryCacheSupply cache = new InMemoryCacheSupply()
                .putDictItem(new CacheSupply.DictItem("IP00000003", "客户名称", "cust_name",
                        "公共集", "文本类", "anc..128", List.of(), null, false));

        GenerationResult r = service(cache).generate(formWith("客户名称", 64));
        assertEquals("VARCHAR(128)", r.fields.get(0).physicalType);
    }

    @Test
    void dictSnapshotIsGlobalAndConsistentAcrossServices() {
        // 字典全局一份：两个服务实例（模拟不同系统）贯标结果一致
        InMemoryCacheSupply cache = new InMemoryCacheSupply()
                .putDictItem(new CacheSupply.DictItem("IP00000006", "借款金额", "loan_amt",
                        "公共集", "金额类", "16(2)", List.of(), null, false));

        GenerationResult a = service(cache).generate(formWith("借款金额"));
        GenerationResult b = service(cache).generate(formWith("借款金额"));
        assertEquals(a.fields.get(0).englishName, b.fields.get(0).englishName);
        assertEquals(a.fields.get(0).physicalType, b.fields.get(0).physicalType);
        assertEquals("DECIMAL(18,2)", a.fields.get(0).physicalType);
    }

    private GenerationService service(InMemoryCacheSupply cache) {
        return new GenerationService(config, cache);
    }

    private static FormInput formWith(String chineseName) {
        return formWith(chineseName, null);
    }

    private static FormInput formWith(String chineseName, Integer length) {
        FormInput form = new FormInput();
        form.tableChineseName = "同业借款合同台账";
        FormField f = new FormField();
        f.chineseName = chineseName;
        f.length = length;
        form.fields.add(f);
        return form;
    }
}
