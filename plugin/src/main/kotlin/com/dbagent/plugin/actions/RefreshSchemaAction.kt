package com.dbagent.plugin.actions

import com.dbagent.plugin.engine.EngineClient
import com.dbagent.plugin.settings.DbAgentSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task

/**
 * 手动刷新 Schema 缓存（Tools 菜单）：后台任务调引擎 /refresh-schema（空参，
 * 引擎按 Token 归属系统回退存储数据源，插件端零 JDBC 配置），结果反馈表数/字段数。
 */
class RefreshSchemaAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = DbAgentSettings.getInstance()
        if (settings.token.isBlank()) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("DbAgent")
                .createNotification(
                    "DbAgent",
                    "请先在「新建数据库表（向导）→ 连接设置」中配置 Token",
                    NotificationType.WARNING
                )
                .notify(project)
            return
        }
        val client = EngineClient(settings.state.engineUrl, settings.token)

        object : Task.Backgroundable(project, "DbAgent 刷新 Schema 缓存…", false) {
            override fun run(indicator: ProgressIndicator) {
                val (title, content, type) = try {
                    val r = client.refreshSchema()
                    Triple(
                        "Schema 缓存刷新完成",
                        "系统 ${r.get("owningSystem")?.asString}：拉取 ${r.get("tables")?.asInt} 张表 / " +
                                "${r.get("fields")?.asInt} 个字段（${r.get("durationMs")?.asLong} ms）",
                        NotificationType.INFORMATION
                    )
                } catch (t: Throwable) {
                    Triple(
                        "Schema 缓存刷新失败",
                        EngineClient.friendlyMessage(t, settings.state.engineUrl),
                        NotificationType.ERROR
                    )
                }
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("DbAgent")
                        .createNotification(title, content, type)
                        .notify(project)
                }
            }
        }.queue()
    }
}
