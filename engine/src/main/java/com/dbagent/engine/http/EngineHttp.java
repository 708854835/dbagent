package com.dbagent.engine.http;

import com.dbagent.engine.cache.CacheSupply;
import com.dbagent.engine.cache.ConfirmedService;
import com.dbagent.engine.db.CacheStore;
import com.dbagent.engine.db.ConfirmedStore;
import com.dbagent.engine.db.DictStore;
import com.dbagent.engine.db.SchemaCacheRefresher;
import com.dbagent.engine.form.ConfirmInput;
import com.dbagent.engine.form.FormInput;
import com.dbagent.engine.gen.GenerationException;
import com.dbagent.engine.gen.GenerationService;
import com.dbagent.engine.llm.LlmAssistService;
import com.dbagent.engine.rules.EngineConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 引擎 HTTP 服务（默认 127.0.0.1:9090，监听绑定内网网卡可配置）：IDEA 插件入口。
 * <pre>
 * POST /generate-ddl    建表向导生成（Token 三方绑定校验后注入 NotesID/归属系统）
 * POST /confirm         已确认候选登记（复核通过的定义回传落库）
 * POST /refresh-schema  按 NotesID 归属系统刷新 Schema 缓存（同步 60s 上限，返回统计；
 *                       不支持刷新其它系统数据）
 * GET  /health          无鉴权轻量四状态（status/rulesLoaded/dictLoaded/schemaCacheReady，
 *                       不探外部连通）
 * </pre>
 * 统一错误信封 {code, message, details?}：AUTH_INVALID(401，过期与无效不区分) /
 * SYSTEM_NOT_MAPPED(403) / VALIDATION_FAILED(400，details 列具体字段) / ENGINE_ERROR(500，
 * 不含敏感信息)。审计日志 append-only 四元组；响应与日志不含密码、完整连接串、Token、主密钥。
 */
public final class EngineHttp {

    /** refresh-schema 同步执行上限（秒） */
    static final int REFRESH_TIMEOUT_SECONDS = 60;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AUTH_INVALID_MESSAGE = "Token 无效或已过期，请联系管理员";

    private final EngineSettings settings;
    private final EngineConfig rules;
    private final CacheSupply cacheSupply;
    private final CacheStore cacheStore;
    private final ConfirmedStore confirmedStore;
    private final DictStore dictStore;
    private final LlmAssistService llmAssist;
    /** 主密钥（启动参数注入，仅用于 hmac: Token 校验；绝不写入日志） */
    private final String masterKey;
    private final AppLogs logs;

    public EngineHttp(EngineSettings settings, EngineConfig rules,
                      CacheSupply cacheSupply, CacheStore cacheStore) {
        this(settings, rules, cacheSupply, cacheStore, null, null, null, null);
    }

    public EngineHttp(EngineSettings settings, EngineConfig rules,
                      CacheSupply cacheSupply, CacheStore cacheStore,
                      ConfirmedStore confirmedStore, DictStore dictStore,
                      LlmAssistService llmAssist) {
        this(settings, rules, cacheSupply, cacheStore, confirmedStore, dictStore,
                llmAssist, null);
    }

    public EngineHttp(EngineSettings settings, EngineConfig rules,
                      CacheSupply cacheSupply, CacheStore cacheStore,
                      ConfirmedStore confirmedStore, DictStore dictStore,
                      LlmAssistService llmAssist, String masterKey) {
        this.settings = settings;
        this.rules = rules;
        this.cacheSupply = cacheSupply;
        this.cacheStore = cacheStore;
        this.confirmedStore = confirmedStore;
        this.dictStore = dictStore;
        this.llmAssist = llmAssist;
        this.masterKey = masterKey;
        this.logs = new AppLogs(java.nio.file.Path.of(settings.logs.dir));
    }

    public HttpServer start() throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress(settings.http.host, settings.http.port), 0);
        server.createContext("/generate-ddl", this::handleGenerateDdl);
        server.createContext("/confirm", this::handleConfirm);
        server.createContext("/refresh-schema", this::handleRefresh);
        server.createContext("/health", this::handleHealth);
        server.start();
        logs.info("引擎 HTTP 服务已启动: " + settings.http.host + ":" + settings.http.port);
        return server;
    }

    private void handleGenerateDdl(HttpExchange exchange) throws IOException {
        EngineSettings.UserContext user = requireAuth(exchange, "generate-ddl");
        if (user == null) {
            return;
        }
        if (user.owningSystem().isBlank()) {
            auditAndRespond(exchange, 403, user, "generate-ddl", "SYSTEM_NOT_MAPPED",
                    errorBody("SYSTEM_NOT_MAPPED", "NotesID 未配置归属系统，请联系管理员", null));
            return;
        }
        try {
            FormInput form = JSON.readValue(readBody(exchange), FormInput.class);
            // 三方绑定校验：表单自称 NotesID 与 Token 绑定不一致即冒用，统一 401
            if (form.notesId != null && !form.notesId.isBlank()
                    && !form.notesId.trim().equals(user.notesId())) {
                auditAndRespond(exchange, 401, user, "generate-ddl", "AUTH_INVALID",
                        errorBody("AUTH_INVALID", AUTH_INVALID_MESSAGE, null));
                return;
            }
            // 校验通过后按 Token 上下文注入身份与归属系统（覆盖表单自称）
            form.notesId = user.notesId();
            form.owningSystem = user.owningSystem();
            if (cacheStore != null && cacheStore.count(user.owningSystem()) == 0) {
                logs.warn("系统 " + user.owningSystem()
                        + " Schema 缓存为空，照常生成（本系统复用能力不可用）");
            }
            Object result = new GenerationService(rules, cacheSupply, llmAssist).generate(form);
            auditAndRespond(exchange, 200, user, "generate-ddl", "OK", result);
        } catch (GenerationException e) {
            auditAndRespond(exchange, 400, user, "generate-ddl", "VALIDATION_FAILED",
                    errorBody("VALIDATION_FAILED", e.getMessage(), e.details()));
        } catch (Exception e) {
            String detail = e.getMessage();
            if (e instanceof com.fasterxml.jackson.core.JsonProcessingException) {
                auditAndRespond(exchange, 400, user, "generate-ddl", "VALIDATION_FAILED",
                        errorBody("VALIDATION_FAILED", "请求体不是合法 JSON", List.of(detail)));
            } else {
                logs.error("generate-ddl 引擎内部错误（" + user.notesId() + "）: "
                        + e.getClass().getSimpleName());
                auditAndRespond(exchange, 500, user, "generate-ddl", "ENGINE_ERROR",
                        errorBody("ENGINE_ERROR", "引擎内部错误", null));
            }
        }
    }

    private void handleConfirm(HttpExchange exchange) throws IOException {
        EngineSettings.UserContext user = requireAuth(exchange, "confirm");
        if (user == null) {
            return;
        }
        if (user.owningSystem().isBlank()) {
            auditAndRespond(exchange, 403, user, "confirm", "SYSTEM_NOT_MAPPED",
                    errorBody("SYSTEM_NOT_MAPPED", "NotesID 未配置归属系统，请联系管理员", null));
            return;
        }
        if (confirmedStore == null || dictStore == null) {
            logs.error("confirm 确认缓存未接入");
            auditAndRespond(exchange, 500, user, "confirm", "ENGINE_ERROR",
                    errorBody("ENGINE_ERROR", "引擎内部错误", null));
            return;
        }
        try {
            ConfirmInput input = JSON.readValue(readBody(exchange), ConfirmInput.class);
            // Token 登录态校验后注入归属系统（覆盖表单自称）
            input.owningSystem = user.owningSystem();
            int count = new ConfirmedService(rules, confirmedStore, dictStore).confirm(input);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("confirmed", count);
            auditAndRespond(exchange, 200, user, "confirm", "OK", ok);
        } catch (GenerationException e) {
            auditAndRespond(exchange, 400, user, "confirm", "VALIDATION_FAILED",
                    errorBody("VALIDATION_FAILED", e.getMessage(), e.details()));
        } catch (Exception e) {
            logs.error("confirm 引擎内部错误（" + user.notesId() + "）: "
                    + e.getClass().getSimpleName());
            auditAndRespond(exchange, 500, user, "confirm", "ENGINE_ERROR",
                    errorBody("ENGINE_ERROR", "引擎内部错误", null));
        }
    }

    private void handleRefresh(HttpExchange exchange) throws IOException {
        EngineSettings.UserContext user = requireAuth(exchange, "refresh-schema");
        if (user == null) {
            return;
        }
        if (user.owningSystem().isBlank()) {
            auditAndRespond(exchange, 403, user, "refresh-schema", "SYSTEM_NOT_MAPPED",
                    errorBody("SYSTEM_NOT_MAPPED", "NotesID 未配置归属系统，请联系管理员", null));
            return;
        }
        try {
            JsonNode body = JSON.readTree(readBody(exchange));
            String jdbcUrl = requiredText(body, "jdbcUrl");
            String username = requiredText(body, "username");
            String password = body.path("password").asText("");
            // 仅可刷新 Token 绑定的本系统；自称其它系统即拒绝
            String requested = body.path("owningSystem").asText(null);
            if (requested != null && !requested.isBlank()
                    && !requested.trim().equals(user.owningSystem())) {
                auditAndRespond(exchange, 403, user, "refresh-schema", "SYSTEM_NOT_MAPPED",
                        errorBody("SYSTEM_NOT_MAPPED",
                                "NotesID 对系统 " + requested.trim() + " 无归属，仅可刷新本系统",
                                null));
                return;
            }
            long started = System.currentTimeMillis();
            ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "refresh-schema");
                t.setDaemon(true);
                return t;
            });
            Future<Integer> future = executor.submit(() -> SchemaCacheRefresher.refresh(
                    jdbcUrl, username, password, user.owningSystem(), cacheStore));
            try {
                int count = future.get(REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("owningSystem", user.owningSystem());
                ok.put("fields", count);
                ok.put("durationMs", System.currentTimeMillis() - started);
                auditAndRespond(exchange, 200, user, "refresh-schema", "OK", ok);
            } catch (TimeoutException te) {
                future.cancel(true);
                logs.error("refresh-schema 超时（" + REFRESH_TIMEOUT_SECONDS + "s 上限）: 系统 "
                        + user.owningSystem());
                auditAndRespond(exchange, 500, user, "refresh-schema", "ENGINE_ERROR",
                        errorBody("ENGINE_ERROR",
                                "Schema 缓存刷新超时（" + REFRESH_TIMEOUT_SECONDS + "s 上限）", null));
            } catch (ExecutionException ee) {
                // 运行日志只记系统与异常类名：不写入连接串/密码/用户名
                logs.error("refresh-schema 失败: 系统 " + user.owningSystem() + ", "
                        + ee.getCause().getClass().getSimpleName());
                auditAndRespond(exchange, 500, user, "refresh-schema", "ENGINE_ERROR",
                        errorBody("ENGINE_ERROR", "Schema 缓存刷新失败，请检查数据源连接参数后重试", null));
            } finally {
                executor.shutdownNow();
            }
        } catch (IllegalArgumentException e) {
            auditAndRespond(exchange, 400, user, "refresh-schema", "VALIDATION_FAILED",
                    errorBody("VALIDATION_FAILED", e.getMessage(), List.of(e.getMessage())));
        } catch (Exception e) {
            logs.error("refresh-schema 引擎内部错误（" + user.notesId() + "）: "
                    + e.getClass().getSimpleName());
            auditAndRespond(exchange, 500, user, "refresh-schema", "ENGINE_ERROR",
                    errorBody("ENGINE_ERROR", "引擎内部错误", null));
        }
    }

    /** 无鉴权健康检查：四状态，不探外部连通（仅本地就绪态）。 */
    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, errorBody("ENGINE_ERROR", "仅支持 GET", null));
            return;
        }
        boolean rulesLoaded = rules != null;
        boolean dictLoaded = dictStore != null && dictStore.count() > 0;
        boolean schemaCacheReady = cacheStore != null && cacheStore.totalCount() > 0;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", rulesLoaded && dictLoaded ? "UP" : "DOWN");
        body.put("rulesLoaded", rulesLoaded);
        body.put("dictLoaded", dictLoaded);
        body.put("schemaCacheReady", schemaCacheReady);
        respond(exchange, 200, body);
    }

    /**
     * Token 校验：无/伪造/过期/被作废（同 NotesID 已有新签发）统一 401，
     * 过期与无效不区分。审计四元组同步落 audit.log。
     */
    private EngineSettings.UserContext requireAuth(HttpExchange exchange, String action)
            throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, errorBody("ENGINE_ERROR", "仅支持 POST", null));
            return null;
        }
        String token = exchange.getRequestHeaders().getFirst("X-Token");
        EngineSettings.UserContext user = settings.authenticate(token, masterKey);
        if (user == null) {
            logs.audit("-", action, "-", "AUTH_INVALID");
            respond(exchange, 401, errorBody("AUTH_INVALID", AUTH_INVALID_MESSAGE, null));
            return null;
        }
        return user;
    }

    private void auditAndRespond(HttpExchange exchange, int status,
                                 EngineSettings.UserContext user, String action,
                                 String result, Object body) throws IOException {
        logs.audit(user.notesId(), action, user.owningSystem(), result);
        respond(exchange, status, body);
    }

    private static byte[] readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return in.readAllBytes();
        }
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 统一错误信封：{code, message, details?}。 */
    private static Map<String, Object> errorBody(String code, String message,
                                                 List<String> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", message == null ? "" : message);
        if (details != null && !details.isEmpty()) {
            body.put("details", details);
        }
        return body;
    }

    private static String requiredText(JsonNode body, String field) {
        String v = body.path(field).asText(null);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("缺少参数: " + field);
        }
        return v;
    }
}
