package com.dbagent.engine.dict;

import com.dbagent.engine.cache.CacheSupply;
import com.dbagent.engine.db.DictStore;
import com.dbagent.engine.db.EngineDatabase;
import com.dbagent.engine.rules.RulesLoader;
import com.dbagent.engine.rules.TypeMappingRules;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 缓存供给缝：字典快照导入语义（多文件合并/整体替换/冲突回滚/记法归一化/非法标记/报告）。
 * 真实 SQLite 文件库冒烟。
 */
class DictImporterTest {

    private final TypeMappingRules typeMapping =
            RulesLoader.load(Path.of("../config")).typeMapping();

    @TempDir
    Path tempDir;

    private Path dictDir;
    private DictStore store;

    @BeforeEach
    void setUp() throws IOException {
        dictDir = Files.createDirectories(tempDir.resolve("dict"));
        store = new DictStore(EngineDatabase.initialize(tempDir.resolve("data")));
    }

    @Test
    void multiFilesAreMergedAndFullyReplaced() throws IOException {
        writeJson("a.json", """
                [ {"id":"IP00000001","chineseName":"币种","englishName":"ccy","importance":"公共集",
                   "dataType":"代码类","dataFormat":"an3"} ]
                """);
        writeJson("b.json", """
                [ {"id":"IP00000002","chineseName":"客户名称","englishName":"cust_name","importance":"公共集",
                   "dataType":"文本类","dataFormat":"anc..128"} ]
                """);

        DictImportReport report = DictImporter.importAll(dictDir, store, typeMapping);
        assertTrue(report.success());
        assertEquals(2, report.imported);
        assertEquals(2, report.files.size());
        assertEquals(2, store.count());

        // 整体替换语义：再次导入只含一个文件的目录，旧数据不残留
        delete("b.json");
        writeJson("a.json", """
                [ {"id":"IP00000009","chineseName":"机构编号","englishName":"org_no","importance":"公共集",
                   "dataType":"编码类","dataFormat":"an10"} ]
                """);
        DictImportReport second = DictImporter.importAll(dictDir, store, typeMapping);
        assertTrue(second.success());
        assertEquals(1, store.count());
        assertTrue(store.findByChineseName("币种").isEmpty());
    }

    @Test
    void conflictingIdRollsBackWholeImportAndKeepsOldSnapshot() throws IOException {
        writeJson("a.json", """
                [ {"id":"IP00000001","chineseName":"币种","englishName":"ccy","importance":"公共集",
                   "dataType":"代码类","dataFormat":"an3"} ]
                """);
        DictImporter.importAll(dictDir, store, typeMapping);
        assertEquals(1, store.count());

        // 第二次导入：同编号不同内容 → 事务回滚，旧快照保留
        writeJson("b.json", """
                [ {"id":"IP00000001","chineseName":"币种代码","englishName":"ccy_code","importance":"自治级",
                   "dataType":"代码类","dataFormat":"an3"} ]
                """);
        DictImportReport report = DictImporter.importAll(dictDir, store, typeMapping);
        assertFalse(report.success());
        assertEquals(List.of("IP00000001"), report.conflicts);
        assertEquals(1, store.count());
        assertEquals("币种", store.findByChineseName("币种").get(0).chineseName());

        // 同编号相同内容重复出现不算冲突
        delete("b.json");
        writeJson("c.json", """
                [ {"id":"IP00000001","chineseName":"币种","englishName":"ccy","importance":"公共集",
                   "dataType":"代码类","dataFormat":"an3"} ]
                """);
        DictImportReport third = DictImporter.importAll(dictDir, store, typeMapping);
        assertTrue(third.success());
        assertEquals(1, store.count());
    }

    @Test
    void oralNotationIsNormalizedAndIllegalFormatsAreMarked() throws IOException {
        writeJson("a.json", """
                [
                  {"id":"IP00000004","chineseName":"机构编号","englishName":"org_no","importance":"公共集",
                   "dataType":"编码类","dataFormat":"6!an"},
                  {"id":"IP00000005","chineseName":"交易日期","englishName":"txn_date","importance":"公共集",
                   "dataType":"日期类","dataFormat":"YYYY-MM-DD"},
                  {"id":"IP00000007","chineseName":"数量","englishName":"qty","importance":"自治级",
                   "dataType":"数值类","dataFormat":"12(0)"},
                  {"id":"IP00000008","chineseName":"客户号","englishName":"cust no!","importance":"自治级",
                   "dataType":"编码类","dataFormat":"an10"}
                ]
                """);

        DictImportReport report = DictImporter.importAll(dictDir, store, typeMapping);
        assertTrue(report.success());
        assertEquals(4, report.imported);
        assertEquals(3, report.totalIllegal());

        // 口述记法 6!an → an6（合法）
        CacheSupply.DictItem org = store.findByChineseName("机构编号").get(0);
        assertEquals("an6", org.dataFormat());
        assertFalse(org.formatIllegal());

        // 日期类仅 YYYYMMDD：YYYY-MM-DD 非法但照常入库
        CacheSupply.DictItem txnDate = store.findByChineseName("交易日期").get(0);
        assertTrue(txnDate.formatIllegal());

        // 数值类不允许 N(0)
        CacheSupply.DictItem qty = store.findByChineseName("数量").get(0);
        assertTrue(qty.formatIllegal());

        // 英文名称非法（含空格/特殊字符）照常入库带标记
        CacheSupply.DictItem custNo = store.findByChineseName("客户号").get(0);
        assertTrue(custNo.formatIllegal());
    }

    @Test
    void publicSetIsReturnedBeforeAutonomous() throws IOException {
        writeJson("a.json", """
                [
                  {"id":"IP00000002","chineseName":"币种","englishName":"currency_type","importance":"自治级",
                   "dataType":"代码类","dataFormat":"an3"},
                  {"id":"IP00000001","chineseName":"币种","englishName":"ccy","importance":"公共集",
                   "dataType":"代码类","dataFormat":"an3"}
                ]
                """);
        DictImporter.importAll(dictDir, store, typeMapping);
        List<CacheSupply.DictItem> hits = store.findByChineseName("币种");
        assertEquals(2, hits.size());
        assertEquals("公共集", hits.get(0).importance());
    }

    private void writeJson(String name, String content) throws IOException {
        Files.writeString(dictDir.resolve(name), content);
    }

    private void delete(String name) throws IOException {
        Files.deleteIfExists(dictDir.resolve(name));
    }
}
