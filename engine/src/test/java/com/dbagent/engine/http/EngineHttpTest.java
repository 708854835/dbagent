package com.dbagent.engine.http;

import com.dbagent.engine.cache.SqliteCacheSupply;
import com.dbagent.engine.db.CacheStore;
import com.dbagent.engine.db.ConfirmedStore;
import com.dbagent.engine.db.DictStore;
import com.dbagent.engine.db.EngineDatabase;
import com.dbagent.engine.rules.EngineConfig;
import com.dbagent.engine.rules.RulesLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP 接口层集成测试：鉴权矩阵（有效/无/伪造/过期/被作废/NotesID 不匹配 × 接口）、
 * 错误信封四码语义、health 四状态、缓存命中 2 秒目标、审计日志落盘。
 */
class EngineHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String VALID = "tok-valid";
    private static final String EXPIRED = "tok-expired";
    private static final String OLD = "tok-old";
    private static final String NEW = "tok-new";
    private static final String UNMAPPED = "tok-unmapped";

    @TempDir
    Path tempDir;

    private EngineSettings settings;
    private HttpServer server;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() throws Exception {
        Path db = EngineDatabase.initialize(tempDir.resolve("data"));
        CacheStore cacheStore = new CacheStore(db);
        // 缓存命中场景：DEMO 系统已有客户编号存量定义
        cacheStore.replaceSystem("DEMO", List.of(
                new CacheStore.Row("t_core_cust", "cust_no", "varchar(64)", "客户编号")));
        DictStore dictStore = new DictStore(db);
        ConfirmedStore confirmedStore = new ConfirmedStore(db);
        EngineConfig rules = RulesLoader.load(Path.of("../config"));
        // 与 serve 启动一致：装载字典（health 的 dictLoaded 判定依赖）
        com.dbagent.engine.dict.DictImporter.importAll(
                Path.of("../config/dict"), dictStore, rules.typeMapping());

        settings = new EngineSettings();
        settings.http.port = 0;
        settings.logs.dir = tempDir.resolve("logs").toString();
        settings.tokens.add(entry(VALID, "zhangsan", "DEMO",
                LocalDate.now().toString()));
        settings.tokens.add(entry(EXPIRED, "wangwu", "DEMO",
                LocalDate.now().minusDays(91).toString()));
        settings.tokens.add(entry(OLD, "lisi", "DEMO",
                LocalDate.now().minusDays(10).toString()));
        settings.tokens.add(entry(NEW, "lisi", "DEMO",
                LocalDate.now().toString()));
        settings.tokens.add(entry(UNMAPPED, "zhaoliu", "",
                LocalDate.now().toString()));

        server = new EngineHttp(settings, rules,
                new SqliteCacheSupply(dictStore, cacheStore, confirmedStore),
                cacheStore, confirmedStore, dictStore, null).start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static EngineSettings.TokenEntry entry(String token, String notesId,
                                                   String system, String issuedAt) {
        EngineSettings.TokenEntry e = new EngineSettings.TokenEntry();
        e.token = token;
        e.notesId = notesId;
        e.owningSystem = system;
        e.issuedAt = issuedAt;
        return e;
    }

    private HttpResponse<String> post(String path, String token, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("X-Token", token);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String formBody() {
        return """
                {"tableChineseName":"客户台账","notesId":"zhangsan","fields":[
                  {"chineseName":"客户编号","required":true,"length":32},
                  {"chineseName":"合同编号","required":true,"length":32}]}
                """;
    }

    // ---------- 鉴权矩阵 × generate-ddl ----------

    @Test
    void validTokenGenerateDdlReturns200WithinTwoSeconds() throws Exception {
        long started = System.currentTimeMillis();
        HttpResponse<String> r = post("/generate-ddl", VALID, formBody());
        long elapsed = System.currentTimeMillis() - started;
        assertEquals(200, r.statusCode(), r.body());
        JsonNode body = JSON.readTree(r.body());
        assertEquals("t_kh", body.path("tableName").asText());
        assertTrue(body.path("ddl").asText().contains("`cust_no`"), r.body());
        // 缓存命中 + 规则可决断：响应 ≤2 秒（验收 5.16）
        assertTrue(elapsed < 2000, "响应耗时 " + elapsed + "ms 超 2 秒目标");
    }

    @Test
    void noTokenReturns401AuthInvalid() throws Exception {
        HttpResponse<String> r = post("/generate-ddl", null, formBody());
        assertEquals(401, r.statusCode());
        JsonNode body = JSON.readTree(r.body());
        assertEquals("AUTH_INVALID", body.path("code").asText());
        assertTrue(body.path("message").asText().contains("联系管理员"));
    }

    @Test
    void forgedTokenReturns401AuthInvalid() throws Exception {
        HttpResponse<String> r = post("/generate-ddl", "forged-token", formBody());
        assertEquals(401, r.statusCode());
        assertEquals("AUTH_INVALID", JSON.readTree(r.body()).path("code").asText());
    }

    @Test
    void expiredTokenReturns401SameAsInvalid() throws Exception {
        HttpResponse<String> r = post("/generate-ddl", EXPIRED, formBody());
        assertEquals(401, r.statusCode());
        JsonNode body = JSON.readTree(r.body());
        assertEquals("AUTH_INVALID", body.path("code").asText());
        // 过期与无效不区分：统一提示联系管理员
        assertTrue(body.path("message").asText().contains("联系管理员"));
    }

    @Test
    void supersededOldTokenReturns401NewTokenWorks() throws Exception {
        // 同 NotesID 签发新 Token 后旧 Token 作废
        HttpResponse<String> oldResp = post("/generate-ddl", OLD, """
                {"tableChineseName":"客户台账","notesId":"lisi","fields":[
                  {"chineseName":"客户编号","required":true,"length":32}]}
                """);
        assertEquals(401, oldResp.statusCode());
        HttpResponse<String> newResp = post("/generate-ddl", NEW, """
                {"tableChineseName":"客户台账","notesId":"lisi","fields":[
                  {"chineseName":"客户编号","required":true,"length":32}]}
                """);
        assertEquals(200, newResp.statusCode(), newResp.body());
    }

    @Test
    void notesIdMismatchReturns401() throws Exception {
        // Token 绑定 zhangsan，表单自称 lisi → 三方绑定不一致拒绝
        String body = formBody().replace("zhangsan", "lisi");
        HttpResponse<String> r = post("/generate-ddl", VALID, body);
        assertEquals(401, r.statusCode());
        assertEquals("AUTH_INVALID", JSON.readTree(r.body()).path("code").asText());
    }

    @Test
    void unmappedNotesIdReturns403SystemNotMapped() throws Exception {
        HttpResponse<String> r = post("/generate-ddl", UNMAPPED, """
                {"tableChineseName":"客户台账","fields":[{"chineseName":"客户编号"}]}
                """);
        assertEquals(403, r.statusCode());
        assertEquals("SYSTEM_NOT_MAPPED", JSON.readTree(r.body()).path("code").asText());
    }

    @Test
    void validationFailedListsFieldDetails() throws Exception {
        HttpResponse<String> r = post("/generate-ddl", VALID, """
                {"tableChineseName":"客户台账","fields":[
                  {"chineseName":"客户编号"},
                  {"chineseName":"  "},
                  {"chineseName":""}]}
                """);
        assertEquals(400, r.statusCode());
        JsonNode body = JSON.readTree(r.body());
        assertEquals("VALIDATION_FAILED", body.path("code").asText());
        // details 列具体字段
        assertTrue(body.path("details").isArray(), r.body());
        assertTrue(body.path("details").toString().contains("第 2 个字段"), r.body());
        assertTrue(body.path("details").toString().contains("第 3 个字段"), r.body());
    }

    // ---------- 鉴权矩阵 × refresh-schema ----------

    @Test
    void refreshNoTokenReturns401() throws Exception {
        HttpResponse<String> r = post("/refresh-schema", null, "{}");
        assertEquals(401, r.statusCode());
        assertEquals("AUTH_INVALID", JSON.readTree(r.body()).path("code").asText());
    }

    @Test
    void refreshForgedTokenReturns401() throws Exception {
        HttpResponse<String> r = post("/refresh-schema", "forged", "{}");
        assertEquals(401, r.statusCode());
    }

    @Test
    void refreshOtherSystemReturns403() throws Exception {
        HttpResponse<String> r = post("/refresh-schema", VALID, """
                {"jdbcUrl":"jdbc:mysql://127.0.0.1:1/x","username":"u","owningSystem":"OTHER"}
                """);
        assertEquals(403, r.statusCode());
        assertEquals("SYSTEM_NOT_MAPPED", JSON.readTree(r.body()).path("code").asText());
    }

    @Test
    void refreshMissingParamsReturns400() throws Exception {
        HttpResponse<String> r = post("/refresh-schema", VALID, "{}");
        assertEquals(400, r.statusCode());
        assertEquals("VALIDATION_FAILED", JSON.readTree(r.body()).path("code").asText());
    }

    @Test
    void refreshFailureReturnsGenericErrorWithoutSensitiveInfo() throws Exception {
        // 不可达数据源：刷新失败；响应与日志不得含密码/连接串
        HttpResponse<String> r = post("/refresh-schema", VALID, """
                {"jdbcUrl":"jdbc:mysql://127.0.0.1:1/secret_db","username":"scott","password":"tiger123"}
                """);
        assertEquals(500, r.statusCode());
        JsonNode body = JSON.readTree(r.body());
        assertEquals("ENGINE_ERROR", body.path("code").asText());
        assertFalse(r.body().contains("tiger123"), r.body());
        assertFalse(r.body().contains("secret_db"), r.body());
        // 失败响应不暴露内部细节
        assertTrue(body.path("message").asText().contains("刷新失败"));
    }

    // ---------- health 四状态 ----------

    @Test
    void healthReturnsFourStatesWithoutAuth() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/health")).GET().build();
        HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode());
        JsonNode body = JSON.readTree(r.body());
        assertEquals("UP", body.path("status").asText());
        assertTrue(body.path("rulesLoaded").asBoolean());
        assertTrue(body.path("dictLoaded").asBoolean());
        assertTrue(body.path("schemaCacheReady").asBoolean());
    }

    // ---------- 主密钥 HMAC Token ----------

    @Test
    void hmacTokenVerifiedOnlyWithMasterKey() throws Exception {
        String masterKey = "test-master-key";
        String rawToken = "opaque-token-abc";
        EngineSettings s = new EngineSettings();
        s.http.port = 0;
        s.logs.dir = tempDir.resolve("logs-hmac").toString();
        s.tokens.add(entry("hmac:" + EngineSettings.hmacSha256Hex(masterKey, rawToken),
                "zhouqi", "DEMO", LocalDate.now().toString()));
        EngineConfig rules = RulesLoader.load(Path.of("../config"));
        Path db = EngineDatabase.initialize(tempDir.resolve("data-hmac"));
        CacheStore cs = new CacheStore(db);
        DictStore ds = new DictStore(db);
        ConfirmedStore cf = new ConfirmedStore(db);
        String body = """
                {"tableChineseName":"客户台账","fields":[{"chineseName":"客户编号"}]}
                """;

        HttpServer withKey = new EngineHttp(s, rules, new SqliteCacheSupply(ds, cs, cf),
                cs, cf, ds, null, masterKey).start();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + withKey.getAddress().getPort() + "/generate-ddl"))
                    .header("X-Token", rawToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            assertEquals(200, client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            withKey.stop(0);
        }

        // 主密钥未注入：hmac 条目无法校验 → 401
        HttpServer noKey = new EngineHttp(s, rules, new SqliteCacheSupply(ds, cs, cf),
                cs, cf, ds, null, null).start();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:" + noKey.getAddress().getPort() + "/generate-ddl"))
                    .header("X-Token", rawToken)
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            assertEquals(401, client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            noKey.stop(0);
        }
    }

    // ---------- 审计日志 ----------

    @Test
    void auditLogRecordsQuadruple() throws Exception {
        post("/generate-ddl", VALID, formBody());
        post("/generate-ddl", "forged", formBody());
        Path audit = tempDir.resolve("logs/audit.log");
        assertTrue(Files.exists(audit));
        List<String> lines = Files.readAllLines(audit);
        assertTrue(lines.stream().anyMatch(l -> l.contains("zhangsan")
                && l.contains("generate-ddl") && l.contains("DEMO") && l.contains("OK")),
                lines.toString());
        assertTrue(lines.stream().anyMatch(l -> l.contains("AUTH_INVALID")),
                lines.toString());
    }
}
