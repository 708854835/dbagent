package com.dbagent.engine.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 引擎配置（config/engine.yaml，非规则配置）：HTTP 端口与 Token → 用户上下文映射。
 * Token 登录态校验走 HTTP，校验后注入 user 上下文（notesId / owningSystem）。
 */
public class EngineSettings {

    public Http http = new Http();
    public Map<String, UserContext> tokens = new LinkedHashMap<>();

    public static class Http {
        public int port = 9090;
    }

    /** Token 校验后注入的用户上下文。 */
    public record UserContext(String notesId, String owningSystem) {
    }

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static EngineSettings load(Path file) {
        if (!Files.exists(file)) {
            return new EngineSettings();
        }
        try (var in = Files.newInputStream(file)) {
            return YAML.readValue(in, EngineSettings.class);
        } catch (IOException e) {
            throw new UncheckedIOException("引擎配置读取失败: " + file, e);
        }
    }

    /** Token → 用户上下文；未知 Token 返回 null（401）。 */
    public UserContext authenticate(String token) {
        if (token == null) {
            return null;
        }
        return tokens.get(token.trim());
    }
}
