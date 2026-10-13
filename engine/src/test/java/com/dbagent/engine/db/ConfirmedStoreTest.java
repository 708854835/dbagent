package com.dbagent.engine.db;

import com.dbagent.engine.cache.CacheSupply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 已确认候选存储：登记/查询、系统隔离、字典快照更新失效（内容不变则不失效）。
 */
class ConfirmedStoreTest {

    @TempDir
    Path tempDir;

    private record Fixture(Path db, DictStore dictStore, ConfirmedStore confirmedStore) {
    }

    private Fixture fixture() {
        Path db = EngineDatabase.initialize(tempDir.resolve(java.util.UUID.randomUUID().toString()));
        return new Fixture(db, new DictStore(db), new ConfirmedStore(db));
    }

    private static CacheSupply.DictItem dictItem(String id, String chineseName) {
        return new CacheSupply.DictItem(id, chineseName, "eng_" + id, "自治级", "文本类",
                "anc..32", List.of(), null, false);
    }

    @Test
    void savedCandidateIsFoundWhileSnapshotMatches() {
        Fixture f = fixture();
        String snapshot = f.dictStore().currentSnapshot();
        f.confirmedStore().save("loan-system", "客户编号", "fp-1",
                "cust_no", "编码类", "VARCHAR(20)", null, snapshot);

        List<CacheSupply.ConfirmedCandidate> found = f.confirmedStore()
                .findValid("loan-system", "客户编号", snapshot);
        assertEquals(1, found.size());
        assertEquals("cust_no", found.get(0).englishName());
        assertEquals("VARCHAR(20)", found.get(0).physicalType());
        assertEquals("编码类", found.get(0).dataType());
        assertEquals("fp-1", found.get(0).contextFingerprint());
    }

    @Test
    void candidateIsInvalidatedByDictSnapshotUpdateButSurvivesIdenticalReimport() {
        Fixture f = fixture();
        f.dictStore().replaceAll(List.of(dictItem("IP1", "币种")));
        String snapshotV1 = f.dictStore().currentSnapshot();
        f.confirmedStore().save("loan-system", "客户编号", "fp-1",
                "cust_no", "编码类", "VARCHAR(20)", null, snapshotV1);

        // 内容不变的重灌：快照标记不变，候选仍然有效
        f.dictStore().replaceAll(List.of(dictItem("IP1", "币种")));
        assertEquals(snapshotV1, f.dictStore().currentSnapshot());
        assertEquals(1, f.confirmedStore().findValid("loan-system", "客户编号",
                f.dictStore().currentSnapshot()).size());

        // 快照内容更新：旧候选失效（查询过滤，不物理删除）
        f.dictStore().replaceAll(List.of(dictItem("IP1", "币种"), dictItem("IP2", "客户名称")));
        String snapshotV2 = f.dictStore().currentSnapshot();
        assertTrue(!snapshotV1.equals(snapshotV2));
        assertTrue(f.confirmedStore().findValid("loan-system", "客户编号", snapshotV2).isEmpty());
        assertEquals(1, f.confirmedStore().count());
    }

    @Test
    void candidatesAreIsolatedByOwningSystem() {
        Fixture f = fixture();
        String snapshot = f.dictStore().currentSnapshot();
        f.confirmedStore().save("loan-system", "客户编号", "fp-1",
                "cust_no", "编码类", "VARCHAR(20)", null, snapshot);

        assertTrue(f.confirmedStore().findValid("deposit-system", "客户编号", snapshot).isEmpty());
        assertEquals(1, f.confirmedStore().findValid("loan-system", "客户编号", snapshot).size());
    }
}
