package com.dbagent.engine.rules;

import com.dbagent.engine.dict.AbbreviationDictionary;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 规则装载缝：config/rules 五 YAML + config/dict 两套缩写词典 → 规则对象。
 * 启动时一次性装载，改配置重启生效。
 */
public final class RulesLoader {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private RulesLoader() {
    }

    public static EngineConfig load(Path configDir) {
        Path rules = configDir.resolve("rules");
        Path dict = configDir.resolve("dict");
        return new EngineConfig(
                read(rules.resolve("naming.yaml"), NamingRules.class),
                read(rules.resolve("type-mapping.yaml"), TypeMappingRules.class),
                read(rules.resolve("constraints.yaml"), ConstraintRules.class),
                read(rules.resolve("audit-fields.yaml"), AuditFieldRules.class),
                read(rules.resolve("llm.yaml"), LlmRules.class),
                readAbbrev(dict.resolve("table-abbrev.yaml")),
                readAbbrev(dict.resolve("field-abbrev.yaml")));
    }

    private static <T> T read(Path file, Class<T> type) {
        try (var in = Files.newInputStream(file)) {
            return YAML.readValue(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException("规则文件读取失败: " + file, e);
        }
    }

    @SuppressWarnings("unchecked")
    private static AbbreviationDictionary readAbbrev(Path file) {
        try (var in = Files.newInputStream(file)) {
            Map<String, Object> root = YAML.readValue(in, Map.class);
            Object dict = root.get("dictionary");
            if (!(dict instanceof Map)) {
                throw new IllegalArgumentException("缩写词典缺少 dictionary 节点: " + file);
            }
            Map<String, String> entries = (Map<String, String>) dict;
            return new AbbreviationDictionary(entries);
        } catch (IOException e) {
            throw new UncheckedIOException("缩写词典读取失败: " + file, e);
        }
    }
}
