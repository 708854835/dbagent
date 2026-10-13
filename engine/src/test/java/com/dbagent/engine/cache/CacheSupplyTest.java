package com.dbagent.engine.cache;

import com.dbagent.engine.decision.Badge;
import com.dbagent.engine.decision.DecisionChain;
import com.dbagent.engine.decision.FieldDecision;
import com.dbagent.engine.form.FormField;
import com.dbagent.engine.rules.EngineConfig;
import com.dbagent.engine.rules.RulesLoader;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 缓存供给缝：决策链经接口读缓存，测试注入内存 fake。
 */
class CacheSupplyTest {

    private static final String SYSTEM = "loan-system";

    private final EngineConfig config = RulesLoader.load(Path.of("../config"));

    @Test
    void emptyCacheFallsThroughToDerivation() {
        DecisionChain chain = newChain(new InMemoryCacheSupply());
        FormField field = new FormField();
        field.chineseName = "合同编号";

        FieldDecision d = chain.decide(field, SYSTEM);
        assertEquals(Badge.DERIVED, d.badge());
        assertEquals("contract_no", d.englishName());
        assertEquals("编码类", d.dataType());
    }

    @Test
    void schemaCacheHitAdoptsExistingDefinitionAndIgnoresUserLength() {
        // 预置存量字段：VARCHAR(64)，用户手填 length=32 应被忽略（ADR-0001 照抄存量）
        InMemoryCacheSupply cache = new InMemoryCacheSupply()
                .putSchemaField(SYSTEM,
                        new CacheSupply.CachedField("合同编号", "contract_no", "VARCHAR(64)"));
        DecisionChain chain = newChain(cache);

        FormField field = new FormField();
        field.chineseName = "合同编号";
        field.length = 32;

        FieldDecision d = chain.decide(field, SYSTEM);
        assertEquals(Badge.REUSE, d.badge());
        assertEquals("contract_no", d.englishName());
        assertEquals("VARCHAR(64)", d.physicalType());
        assertNull(d.dataType());
    }

    @Test
    void dictItemHitIsStandardizedWhenSchemaCacheMisses() {
        InMemoryCacheSupply cache = new InMemoryCacheSupply()
                .putDictItem(new CacheSupply.DictItem("IP00000006", "借款金额", "loan_amt",
                        "公共集", "金额类", "16(2)", List.of(), null, false));
        DecisionChain chain = newChain(cache);

        FormField field = new FormField();
        field.chineseName = "借款金额";

        FieldDecision d = chain.decide(field, SYSTEM);
        assertEquals(Badge.STANDARDIZED, d.badge());
        assertEquals("loan_amt", d.englishName());
        assertEquals("DECIMAL(18,2)", d.physicalType());
    }

    @Test
    void conflictingSchemaHitsAreMarkedForReviewWithCandidates() {
        InMemoryCacheSupply cache = new InMemoryCacheSupply()
                .putSchemaField(SYSTEM, new CacheSupply.CachedField("备注", "remark", "VARCHAR(128)"))
                .putSchemaField(SYSTEM, new CacheSupply.CachedField("备注", "memo", "VARCHAR(256)"));
        DecisionChain chain = newChain(cache);

        FormField field = new FormField();
        field.chineseName = "备注";

        FieldDecision d = chain.decide(field, SYSTEM);
        assertEquals(Badge.NEEDS_REVIEW, d.badge());
        assertEquals(2, d.candidates().size());
    }

    private DecisionChain newChain(CacheSupply cache) {
        return new DecisionChain(cache, config.fieldAbbrev(), config.typeMapping(), config.naming());
    }
}
