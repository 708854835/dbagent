package com.dbagent.plugin.engine

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * 引擎 HTTP 客户端：JDK HttpClient + 平台内嵌 Gson。
 * 错误信封 {code, message, details?} 统一映射为 EngineException。
 */
class EngineClient(baseUrl: String, private val token: String) {

    /** 引擎错误信封（四码语义见引擎 EngineHttp）。 */
    class EngineException(val code: String, message: String, val status: Int) : Exception(message)

    private val baseUrl: String = baseUrl.trimEnd('/')

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    private fun post(path: String, body: JsonObject): JsonObject {
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path))
            // refresh-schema 引擎侧 60s 上限，留余量
            .timeout(Duration.ofSeconds(70))
            .header("Content-Type", "application/json")
            .header("X-Token", token)
            .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        val json = JsonParser.parseString(response.body()).asJsonObject
        if (response.statusCode() !in 200..299) {
            throw EngineException(
                json.get("code")?.asString ?: "ENGINE_ERROR",
                json.get("message")?.asString ?: "引擎错误（HTTP ${response.statusCode()}）",
                response.statusCode()
            )
        }
        return json
    }

    /** 建表向导生成（表单 JSON 原样透传，NotesID/归属系统由 Token 上下文注入）。 */
    fun generateDdl(form: JsonObject): JsonObject = post("/generate-ddl", form)

    /** 已确认候选登记（复核通过的定义回传落库）。 */
    fun confirm(payload: JsonObject): JsonObject = post("/confirm", payload)

    /** 空参刷新：引擎侧存储数据源按 Token 归属系统回退（插件端零 JDBC 配置）。 */
    fun refreshSchema(): JsonObject = post("/refresh-schema", JsonObject())

    /** 无鉴权健康检查（status/rulesLoaded/dictLoaded/schemaCacheReady）。 */
    fun health(): JsonObject {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/health"))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        return JsonParser.parseString(response.body()).asJsonObject
    }

    companion object {
        private val GSON = Gson()

        /** 异常 → 用户可读提示：引擎不可达/鉴权失败/校验失败分级友好提示，不崩溃。 */
        fun friendlyMessage(t: Throwable, engineUrl: String): String = when (t) {
            is EngineException -> when (t.code) {
                "AUTH_INVALID" -> "Token 无效或已过期，请联系管理员重新签发"
                "SYSTEM_NOT_MAPPED" -> t.message ?: "NotesID 未配置归属系统，请联系管理员"
                "VALIDATION_FAILED" -> "表单校验未通过：" + (t.message ?: "")
                else -> "引擎内部错误，请联系管理员查看引擎日志"
            }

            is java.net.http.HttpConnectTimeoutException,
            is java.net.ConnectException ->
                "引擎不可达（$engineUrl），请确认引擎服务已启动后重试"

            else -> "操作失败：" + (t.message ?: t.javaClass.simpleName)
        }
    }
}
