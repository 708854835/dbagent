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
import com.dbagent.engine.rules.EngineConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 引擎 HTTP 服务（9090）：IDEA 插件入口。
 * POST /generate              建表向导生成（Token 校验后注入 user 上下文）
 * POST /confirm               已确认候选登记（复核通过的定义回传落库）
 * POST /schema-cache/refresh  本系统 Schema 缓存拉取（information_schema 全量含视图）
 * CLI 无 Token 场景继续可用（不经 HTTP）。
 */
public final class EngineHttp {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final EngineSettings settings;
    private final EngineConfig rules;
    private final CacheSupply cacheSupply;
    private final CacheStore cacheStore;
    private final ConfirmedStore confirmedStore;
    private final DictStore dictStore;

    public EngineHttp(EngineSettings settings, EngineConfig rules,
                      CacheSupply cacheSupply, CacheStore cacheStore) {
        this(settings, rules, cacheSupply, cacheStore, null, null);
    }

    public EngineHttp(EngineSettings settings, EngineConfig rules,
                      CacheSupply cacheSupply, CacheStore cacheStore,
                      ConfirmedStore confirmedStore, DictStore dictStore) {
        this.settings = settings;
        this.rules = rules;
        this.cacheSupply = cacheSupply;
        this.cacheStore = cacheStore;
        this.confirmedStore = confirmedStore;
        this.dictStore = dictStore;
    }

    public HttpServer start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(settings.http.port), 0);
        server.createContext("/generate", this::handleGenerate);
        server.createContext("/confirm", this::handleConfirm);
        server.createContext("/schema-cache/refresh", this::handleRefresh);
        server.start();
        return server;
    }

    private void handleGenerate(HttpExchange exchange) throws IOException {
        EngineSettings.UserContext user = requireAuth(exchange);
        if (user == null) {
            return;
        }
        try {
            FormInput form = JSON.readValue(readBody(exchange), FormInput.class);
            // Token 登录态校验后注入 user 上下文（覆盖表单内的自称身份）
            form.notesId = user.notesId();
            form.owningSystem = user.owningSystem();
            Object result = new GenerationService(rules, cacheSupply).generate(form);
            respond(exchange, 200, result);
        } catch (GenerationException e) {
            respond(exchange, 422, errorBody(e.code(), e.getMessage()));
        } catch (Exception e) {
            respond(exchange, 400, errorBody("BAD_REQUEST", e.getMessage()));
        }
    }

    private void handleConfirm(HttpExchange exchange) throws IOException {
        EngineSettings.UserContext user = requireAuth(exchange);
        if (user == null) {
            return;
        }
        if (confirmedStore == null || dictStore == null) {
            respond(exchange, 503, errorBody("UNAVAILABLE", "确认缓存未接入"));
            return;
        }
        try {
            ConfirmInput input = JSON.readValue(readBody(exchange), ConfirmInput.class);
            // Token 登录态校验后注入 user 上下文（覆盖表单内的自称身份）
            input.owningSystem = user.owningSystem();
            int count = new ConfirmedService(rules, confirmedStore, dictStore).confirm(input);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("confirmed", count);
            respond(exchange, 200, ok);
        } catch (GenerationException e) {
            respond(exchange, 422, errorBody(e.code(), e.getMessage()));
        } catch (Exception e) {
            respond(exchange, 400, errorBody("BAD_REQUEST", e.getMessage()));
        }
    }

    private void handleRefresh(HttpExchange exchange) throws IOException {
        EngineSettings.UserContext user = requireAuth(exchange);
        if (user == null) {
            return;
        }
        try {
            JsonNode body = JSON.readTree(readBody(exchange));
            String jdbcUrl = requiredText(body, "jdbcUrl");
            String username = requiredText(body, "username");
            String password = body.path("password").asText("");
            // 缓存归属系统默认取 Token 上下文的本系统
            String owningSystem = body.path("owningSystem").isMissingNode()
                    || body.path("owningSystem").isNull()
                    ? user.owningSystem()
                    : body.path("owningSystem").asText();
            int count = SchemaCacheRefresher.refresh(jdbcUrl, username, password,
                    owningSystem, cacheStore);
            Map<String, Object> ok = new LinkedHashMap<>();
            ok.put("owningSystem", owningSystem);
            ok.put("fields", count);
            respond(exchange, 200, ok);
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, errorBody("BAD_REQUEST", e.getMessage()));
        } catch (Exception e) {
            respond(exchange, 502, errorBody("REFRESH_FAILED", e.getMessage()));
        }
    }

    private EngineSettings.UserContext requireAuth(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            respond(exchange, 405, errorBody("METHOD_NOT_ALLOWED", "仅支持 POST"));
            return null;
        }
        String token = exchange.getRequestHeaders().getFirst("X-Token");
        EngineSettings.UserContext user = settings.authenticate(token);
        if (user == null) {
            respond(exchange, 401, errorBody("UNAUTHORIZED", "Token 缺失或无效"));
            return null;
        }
        return user;
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

    private static Map<String, Object> errorBody(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", Map.of("code", code, "message", message == null ? "" : message));
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
