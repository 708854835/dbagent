package com.dbagent.engine.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 引擎配置（config/engine.yaml，非规则配置）：HTTP 监听、日志目录、Token 清单。
 * <p>
 * Token 模型：opaque、配置文件维护、签发日起 90 天有效、重启轮换（部署脚本重写配置）；
 * NotesID↔Token 一对一（同 NotesID 仅最新签发有效，签发新即作废旧）；
 * Token↔NotesID↔归属系统三方绑定，校验通过后按 NotesID 反查归属系统。
 * Token 过期与无效不区分；支持明文或 "hmac:&lt;hex&gt;"（HMAC-SHA256，主密钥启动参数注入）。
 */
public class EngineSettings {

    /** Token 签发后有效天数 */
    static final int TOKEN_VALID_DAYS = 90;
    /** Token 值前缀：HMAC-SHA256(主密钥, 明文 Token) 的十六进制 */
    static final String HMAC_PREFIX = "hmac:";

    public Http http = new Http();
    public Logs logs = new Logs();
    public List<TokenEntry> tokens = new ArrayList<>();
    /** 归属系统 → 数据源连接（refresh-schema 空参回退，插件端零 JDBC 配置） */
    public Map<String, DataSource> datasources = new HashMap<>();

    public static class Http {
        public int port = 9090;
        /** 监听绑定地址（内网网卡，不暴露公网），默认仅回环 */
        public String host = "127.0.0.1";
    }

    public static class Logs {
        /** 日志目录：审计日志 audit.log 与运行日志 engine.log 分离 */
        public String dir = "logs";
    }

    /** 数据源连接：refresh-schema 请求未带 jdbcUrl 时按归属系统回退使用。 */
    public static class DataSource {
        public String jdbcUrl;
        public String username;
        public String password;
    }

    /** Token 条目：opaque 值（明文或 hmac:hex）+ 绑定的 NotesID 与归属系统 + 签发日期。 */
    public static class TokenEntry {
        public String token;
        public String notesId;
        public String owningSystem;
        /** ISO 日期（如 2026-10-13），签发日起 90 天有效 */
        public String issuedAt;
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
            EngineSettings settings = YAML.readValue(in, EngineSettings.class);
            settings.validateEntries(file);
            return settings;
        } catch (IOException e) {
            throw new UncheckedIOException("引擎配置读取失败: " + file, e);
        }
    }

    private void validateEntries(Path file) {
        for (TokenEntry e : tokens) {
            if (e.token == null || e.token.isBlank()
                    || e.notesId == null || e.notesId.isBlank()) {
                throw new IllegalArgumentException(
                        "引擎配置 Token 条目缺少 token/notesId: " + file);
            }
            try {
                LocalDate.parse(e.issuedAt == null ? "" : e.issuedAt.trim());
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException(
                        "引擎配置 Token 条目 issuedAt 非法（须 ISO 日期）: " + e.notesId, ex);
            }
        }
    }

    /**
     * Token 三方绑定校验：值匹配（明文或 HMAC）+ 同 NotesID 最新签发 + 90 天未过期。
     * 过期与无效不区分，一律返回 null（调用方统一 401）。
     *
     * @param masterKey 主密钥（启动参数注入；仅校验 hmac: 条目时需要，可为 null）
     */
    public UserContext authenticate(String presentedToken, String masterKey) {
        if (presentedToken == null || presentedToken.isBlank()) {
            return null;
        }
        String presented = presentedToken.trim();
        TokenEntry matched = null;
        for (TokenEntry e : tokens) {
            if (tokenMatches(e, presented, masterKey)) {
                matched = e;
                break;
            }
        }
        if (matched == null) {
            return null;
        }
        // 一对一：同 NotesID 仅最新签发有效（签发新即作废旧）
        if (newestFor(matched.notesId) != matched) {
            return null;
        }
        LocalDate issued = LocalDate.parse(matched.issuedAt.trim());
        if (issued.plusDays(TOKEN_VALID_DAYS).isBefore(LocalDate.now())) {
            return null;
        }
        return new UserContext(matched.notesId,
                matched.owningSystem == null ? "" : matched.owningSystem.trim());
    }

    /** 同 NotesID 最新签发条目（同日期取清单靠后者）。 */
    private TokenEntry newestFor(String notesId) {
        TokenEntry newest = null;
        for (TokenEntry e : tokens) {
            if (e.notesId.trim().equals(notesId)
                    && (newest == null
                    || !LocalDate.parse(e.issuedAt.trim())
                    .isBefore(LocalDate.parse(newest.issuedAt.trim())))) {
                newest = e;
            }
        }
        return newest;
    }

    private static boolean tokenMatches(TokenEntry entry, String presented, String masterKey) {
        String configured = entry.token.trim();
        if (configured.startsWith(HMAC_PREFIX)) {
            return masterKey != null && !masterKey.isBlank()
                    && hmacSha256Hex(masterKey, presented)
                    .equalsIgnoreCase(configured.substring(HMAC_PREFIX.length()).trim());
        }
        return configured.equals(presented);
    }

    /** HMAC-SHA256(主密钥, 明文 Token) 十六进制，供配置 hmac: 条目与启动校验使用。 */
    public static String hmacSha256Hex(String masterKey, String token) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(masterKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }
}
