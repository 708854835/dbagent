package com.dbagent.plugin.actions

import com.dbagent.plugin.engine.EngineClient
import com.dbagent.plugin.preview.ResultPreviewDialog
import com.dbagent.plugin.wizard.TableWizardDialog
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task

/**
 * 建表向导入口（Tools 菜单 + 编辑器右键菜单）。
 * 向导 → 后台任务调引擎生成（不阻塞 EDT）→ 预览对话框。
 * 引擎不可达/鉴权失败等异常统一友好提示，不崩溃。
 */
class NewTableWizardAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val wizard = TableWizardDialog(project)
        if (!wizard.showAndGet()) return
        wizard.saveSettings()
        val form = wizard.buildForm()
        val client = EngineClient(wizard.engineUrl(), wizard.token())

        object : Task.Backgroundable(project, "DbAgent 生成中…", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val result = client.generateDdl(form)
                    ApplicationManager.getApplication().invokeLater {
                        ResultPreviewDialog(project, result, form, client).show()
                    }
                } catch (t: Throwable) {
                    ApplicationManager.getApplication().invokeLater {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("DbAgent")
                            .createNotification(
                                "DbAgent 生成失败",
                                EngineClient.friendlyMessage(t, wizard.engineUrl()),
                                NotificationType.ERROR
                            )
                            .notify(project)
                    }
                }
            }
        }.queue()
    }
}
