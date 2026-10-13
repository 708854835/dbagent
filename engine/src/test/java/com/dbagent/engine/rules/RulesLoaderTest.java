package com.dbagent.engine.rules;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则装载缝：YAML → 规则对象；改动配置重启（重新装载）即生效。
 */
class RulesLoaderTest {

    private static final Path CONFIG = Path.of("../config");

    @Test
    void loadsAllRuleFilesIntoObjects() {
        EngineConfig config = RulesLoader.load(CONFIG);

        // 命名
        assertEquals("t_", config.naming().table.prefix);
        assertEquals(32, config.naming().table.preferredMaxBytes);
        assertEquals(64, config.naming().table.absoluteMaxBytes);
        assertEquals(30, config.naming().field.maxChars);

        // 类型映射：10 类齐全
        assertEquals(10, config.typeMapping().types.size());
        assertEquals("DECIMAL", config.typeMapping().type("金额类").physical);
        assertEquals("编码类", config.typeMapping().inferDataType("客户编号"));
        assertEquals("金额类", config.typeMapping().inferDataType("借款金额"));
        assertEquals("日期时间类", config.typeMapping().inferDataType("交易时间"));
        assertEquals("文本类", config.typeMapping().inferDataType("莫名其妙词"));

        // 约束
        assertEquals("utf8mb4", config.constraints().charset);
        assertEquals("InnoDB", config.constraints().engine);
        assertEquals(30, config.constraints().maxFields);
        assertFalse(config.constraints().foreignKeysAllowed);

        // 审计字段
        assertEquals(2, config.auditFields().fields.size());
        assertTrue(config.auditFields().allAliases().contains("创建时间"));
        assertTrue(config.auditFields().allAliases().contains("更新时间"));

        // LLM 占位：本期关闭
        assertFalse(config.llm().enabled);

        // 两套独立缩写词典（表名简称连写，字段名下划线分割）
        assertTrue(config.tableAbbrev().derive("同业借款合同台账").fullyCovered());
        assertEquals(List.of("ty", "jk", "ht"), config.tableAbbrev().derive("同业借款合同台账").parts());
        assertEquals("contract_no", config.fieldAbbrev().derive("合同编号").joined());
    }

    @Test
    void configChangeTakesEffectOnReload() throws IOException {
        // 拷贝配置到临时目录并加一项审计字段，模拟"改配置重启生效"
        Path tmp = Files.createTempDirectory("dbagent-rules");
        try {
            copyDir(CONFIG, tmp);
            Path audit = tmp.resolve("rules/audit-fields.yaml");
            String yaml = Files.readString(audit);
            yaml += """
                      - englishName: DELETED_FLAG
                        physicalType: TINYINT(1)
                        comment: 删除标志
                        aliasList:
                          - 删除标志
                        defaultValue: '0'
                    """;
            Files.writeString(audit, yaml);

            EngineConfig reloaded = RulesLoader.load(tmp);
            assertEquals(3, reloaded.auditFields().fields.size());
            assertTrue(reloaded.auditFields().allAliases().contains("删除标志"));
        } finally {
            try (Stream<Path> walk = Files.walk(tmp)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
    }

    private static void copyDir(Path from, Path to) throws IOException {
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path src : walk.toList()) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dst);
                } else {
                    Files.copy(src, dst);
                }
            }
        }
    }
}
