package com.dbagent.engine.gen;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生成服务缝（主缝）：表单 JSON 进 → 生成结果出，推导路径端到端。
 */
class GenerationServiceTest {

    private static GenerationService service;

    @BeforeAll
    static void setUp() {
        EngineConfig config = RulesLoader.load(Path.of("../config"));
        service = new GenerationService(config, new InMemoryCacheSupply());
    }

    @Test
    void demoFormGeneratesCompliantDdlViaDerivation() {
        GenerationResult r = service.generate(demoForm());

        assertEquals("t_tyjkht", r.tableName);
        assertFalse(r.tableNameNeedsReview);

        String ddl = r.ddl;
        assertTrue(ddl.contains("CREATE TABLE `t_tyjkht`"), ddl);
        assertTrue(ddl.contains("ENGINE=InnoDB"), ddl);
        assertTrue(ddl.contains("DEFAULT CHARSET=utf8mb4"), ddl);
        assertFalse(ddl.contains("FOREIGN KEY"), ddl);
        // 自增 id 主键（用户未勾选时补齐）
        assertTrue(ddl.contains("`id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT"), ddl);
        assertTrue(ddl.contains("PRIMARY KEY (`id`)"), ddl);
        // 审计字段
        assertTrue(ddl.contains("`CREATE_TIME` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP"), ddl);
        assertTrue(ddl.contains("ON UPDATE CURRENT_TIMESTAMP"), ddl);
        // NOT NULL + 默认值 + 逐字段 COMMENT
        assertTrue(ddl.contains("`contract_no` VARCHAR(32) NOT NULL DEFAULT '' COMMENT '合同编号'"), ddl);
        assertTrue(ddl.contains("`loan_amount` DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '借款金额'"), ddl);
        assertTrue(ddl.contains("COMMENT '同业借款合同台账"), ddl);

        // 逐字段来源徽章：本切片全部"推导·待登记"
        assertEquals(6, r.fields.size());
        assertTrue(r.fields.stream().allMatch(f -> "推导·待登记".equals(f.badge)));
        assertEquals(
                List.of("contract_no", "loan_bank_name", "loan_amount", "loan_date",
                        "repayment_date", "remark"),
                r.fields.stream().map(f -> f.englishName).toList());

        // 待登记清单：字典项编号留空
        assertEquals(6, r.pendingRegistration.size());
        assertTrue(r.pendingRegistration.stream().allMatch(p -> p.dictItemId.isEmpty()));
        assertEquals("contract_no", r.pendingRegistration.get(0).englishName);
        assertEquals("金额类", r.pendingRegistration.get(2).dataType);
        assertEquals("16(2)", r.pendingRegistration.get(2).dataFormat);
    }

    @Test
    void fieldCountIncludingAutoFilledOver30IsBlocked() {
        FormInput form = new FormInput();
        form.tableChineseName = "同业借款合同台账";
        // 28 录入字段 + 自增 id + 2 审计字段 = 31 > 30
        for (int i = 0; i < 28; i++) {
            FormField f = new FormField();
            f.chineseName = "合同编号" + i;
            form.fields.add(f);
        }

        GenerationException e = assertThrows(GenerationException.class, () -> service.generate(form));
        assertEquals("FIELD_COUNT_EXCEEDED", e.code());
        assertTrue(e.getMessage().contains("拆表"), e.getMessage());
    }

    @Test
    void auditFieldsAreNotDuplicatedWhenUserSuppliedAlias() {
        FormInput form = demoForm();
        FormField createTime = new FormField();
        createTime.chineseName = "创建时间";
        form.fields.add(createTime);

        GenerationResult r = service.generate(form);
        // 用户已录入"创建时间"（别名命中），不再自动补齐 CREATE_TIME
        assertFalse(r.ddl.contains("`CREATE_TIME`"), r.ddl);
        assertTrue(r.ddl.contains("`UPDATE_TIME`"), r.ddl);
    }

    @Test
    void uncoveredWordIsMarkedForReviewAndExcludedFromDdl() {
        FormInput form = demoForm();
        FormField exotic = new FormField();
        exotic.chineseName = "特殊目的载体";
        form.fields.add(exotic);

        GenerationResult r = service.generate(form);
        GenerationResult.FieldView view = r.fields.stream()
                .filter(f -> "特殊目的载体".equals(f.chineseName))
                .findFirst().orElseThrow();
        assertEquals("需人工复核", view.badge);
        assertFalse(r.ddl.contains("特殊目的载体"), r.ddl);
        assertEquals(6, r.pendingRegistration.size());
        assertFalse(r.warnings.isEmpty());
    }

    @Test
    void userSelectedPrimaryKeyIsRespected() {
        FormInput form = demoForm();
        form.fields.get(0).primaryKey = true;

        GenerationResult r = service.generate(form);
        assertTrue(r.ddl.contains("PRIMARY KEY (`contract_no`)"), r.ddl);
        assertFalse(r.ddl.contains("AUTO_INCREMENT"), r.ddl);
    }

    @Test
    void lockedTableNameIsCopiedWithoutDerivation() {
        FormInput form = demoForm();
        form.lockedTableName = "t_mytable";

        GenerationResult r = service.generate(form);
        // 锁定表名照抄不重算，且不标人工复核
        assertEquals("t_mytable", r.tableName);
        assertFalse(r.tableNameNeedsReview);
        assertTrue(r.ddl.contains("CREATE TABLE `t_mytable`"), r.ddl);
    }

    @Test
    void lockedTableNameIllegalIsRejected() {
        FormInput form = demoForm();
        form.lockedTableName = "T_Bad-Name";

        GenerationException e = assertThrows(GenerationException.class, () -> service.generate(form));
        assertEquals("VALIDATION_FAILED", e.code());
        assertTrue(e.getMessage().contains("锁定表名"), e.getMessage());
    }

    static FormInput demoForm() {
        FormInput form = new FormInput();
        form.tableChineseName = "同业借款合同台账";
        form.description = "同业借款合同登记台账";
        form.notesId = "zhangsan";
        addField(form, "合同编号", 32, null);
        addField(form, "借款银行名称", 128, null);
        addField(form, "借款金额", null, null);
        addField(form, "借款日期", null, null);
        addField(form, "还款日期", null, null);
        addField(form, "备注", 256, null);
        return form;
    }

    private static void addField(FormInput form, String chineseName, Integer length, Integer precision) {
        FormField f = new FormField();
        f.chineseName = chineseName;
        f.length = length;
        f.precision = precision;
        form.fields.add(f);
    }
}
