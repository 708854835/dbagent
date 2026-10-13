package com.dbagent.plugin.preview

import com.dbagent.plugin.engine.EngineClient
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableModel

/**
 * 生成结果预览：逐字段来源徽章表格（需人工复核高亮）、可编辑英文名/物理类型/表名、
 * “重新生成”按 locked 增量语义回传用户修正；索引复选框默认全选（主键固定不参与）；
 * 最终 SQL = 引擎 DDL + 选中索引 ADD INDEX；支持复制 SQL / 插入编辑器；
 * “完成并登记”将复核通过定义回传 /confirm 落库。
 */
class ResultPreviewDialog(
    private val project: Project,
    private var result: JsonObject,
    private val originalForm: JsonObject,
    private val client: EngineClient
) : DialogWrapper(project, true) {

    /** 行模型：标记用户是否手工修正（修正行重生成时 locked 回传）。 */
    private class Row {
        var chineseName: String = ""
        var badge: String = ""
        var englishName: String = ""
        var physicalType: String = ""
        var note: String = ""
        var edited: Boolean = false
    }

    private val rows = mutableListOf<Row>()
    private val tableNameField = JBTextField()
    private var lockedTableName: String? = null
    private var warningsLabel = JBLabel(" ")
    private val indexPanel = JPanel(FlowLayout(FlowLayout.LEFT))
    private val indexChecks = mutableListOf<Pair<JBCheckBox, IndexEntry>>()

    private class IndexEntry(val name: String, val columns: List<String>, val fixed: Boolean)

    private val model = object : DefaultTableModel(arrayOf("字段中文名", "来源徽章", "英文名", "物理类型", "说明"), 0) {
        override fun isCellEditable(row: Int, column: Int): Boolean = column == 2 || column == 3
        override fun setValueAt(aValue: Any?, row: Int, column: Int) {
            super.setValueAt(aValue, row, column)
            if (column == 2) rows[row].englishName = (aValue as? String).orEmpty()
            if (column == 3) rows[row].physicalType = (aValue as? String).orEmpty()
            rows[row].edited = true
        }
    }
    private val table = object : JBTable(model) {
        override fun prepareRenderer(renderer: javax.swing.table.TableCellRenderer, row: Int, column: Int): Component {
            val c = super.prepareRenderer(renderer, row, column)
            val modelRow = convertRowIndexToModel(row)
            // 需人工复核高亮：浅红底提示必须处理
            c.background = if (rows.getOrNull(modelRow)?.badge == "需人工复核")
                JBColor(java.awt.Color(255, 235, 235), java.awt.Color(90, 60, 60))
            else if (isRowSelected(row)) selectionBackground else background
            return c
        }
    }

    init {
        title = "DbAgent 生成结果预览"
        loadResult(result)
        init()
    }

    private fun loadResult(result: JsonObject) {
        rows.clear()
        model.rowCount = 0
        val fields = result.getAsJsonArray("fields") ?: JsonArray()
        for (el in fields) {
            val f = el.asJsonObject
            val row = Row()
            row.chineseName = f.get("chineseName")?.asString.orEmpty()
            row.badge = f.get("badge")?.asString.orEmpty()
            row.englishName = f.get("englishName")?.asString.orEmpty()
            row.physicalType = f.get("physicalType")?.asString.orEmpty()
            row.note = f.get("note")?.asString.orEmpty()
            rows.add(row)
            model.addRow(arrayOf(row.chineseName, row.badge, row.englishName, row.physicalType, row.note))
        }
        tableNameField.text = result.get("tableName")?.asString.orEmpty()
        lockedTableName = null
        val warnings = result.getAsJsonArray("warnings") ?: JsonArray()
        warningsLabel.text = if (warnings.size() == 0) " "
        else "<html>引擎提示：" + warnings.joinToString("<br>") { it.asString } + "</html>"
        indexChecks.clear()
        indexPanel.removeAll()
        val suggestions = result.getAsJsonArray("indexSuggestions") ?: JsonArray()
        for (el in suggestions) {
            val s = el.asJsonObject
            val name = s.get("name")?.asString.orEmpty()
            val columns = s.getAsJsonArray("columns")?.map { it.asString } ?: emptyList()
            val fixed = s.get("fixed")?.asBoolean ?: false
            val check = JBCheckBox("$name (${columns.joinToString(", ")})" + if (fixed) "（主键，已含于 DDL）" else "")
            // 索引复选框默认全选；主键固定不参与勾选
            check.isSelected = !fixed
            check.isEnabled = !fixed
            indexChecks.add(check to IndexEntry(name, columns, fixed))
            indexPanel.add(check)
        }
        indexPanel.revalidate()
        indexPanel.repaint()
    }

    override fun createCenterPanel(): JComponent {
        val regenerate = JButton("重新生成（锁定已修正项）")
        regenerate.addActionListener { regenerate() }
        val top = JPanel(BorderLayout(0, JBUI.scale(4)))
        val nameRow = JPanel(BorderLayout(JBUI.scale(6), 0))
        nameRow.add(JBLabel("物理表名："), BorderLayout.WEST)
        nameRow.add(tableNameField, BorderLayout.CENTER)
        nameRow.add(regenerate, BorderLayout.EAST)
        top.add(nameRow, BorderLayout.NORTH)
        top.add(warningsLabel, BorderLayout.CENTER)

        val bottom = JPanel(BorderLayout())
        bottom.add(JBLabel("索引（默认全选，主键不参与）："), BorderLayout.NORTH)
        bottom.add(indexPanel, BorderLayout.CENTER)

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT))
        val copy = JButton("复制 SQL")
        copy.addActionListener { copySql() }
        val insert = JButton("插入编辑器")
        insert.addActionListener { insertSql() }
        buttons.add(copy)
        buttons.add(insert)
        bottom.add(buttons, BorderLayout.SOUTH)

        val root = JPanel(BorderLayout(0, JBUI.scale(6)))
        root.border = JBUI.Borders.empty(8)
        root.add(top, BorderLayout.NORTH)
        root.add(JBScrollPane(table), BorderLayout.CENTER)
        root.add(bottom, BorderLayout.SOUTH)
        root.preferredSize = JBUI.size(760, 520)
        return root
    }

    /** 重新生成：用户修正行 locked=true 回传英文名/物理类型；表名改过则 lockedTableName。 */
    private fun regenerate() {
        val form = originalForm.deepCopy()
        val editedName = tableNameField.text.trim()
        if (editedName.isNotEmpty() && editedName != result.get("tableName")?.asString) {
            form.addProperty("lockedTableName", editedName)
        }
        lockedTableName?.let { form.addProperty("lockedTableName", it) }
        val formFields = form.getAsJsonArray("fields") ?: JsonArray()
        for (el in formFields) {
            val f = el.asJsonObject
            val row = rows.firstOrNull { it.chineseName == f.get("chineseName")?.asString }
            if (row != null && row.edited) {
                f.addProperty("locked", true)
                f.addProperty("englishName", row.englishName)
                f.addProperty("physicalType", row.physicalType)
            }
        }
        try {
            val regenerated = client.generateDdl(form)
            result = regenerated
            lockedTableName = form.get("lockedTableName")?.asString
            loadResult(regenerated)
        } catch (t: Throwable) {
            Messages.showErrorDialog(
                project,
                EngineClient.friendlyMessage(t, clientBaseUrl()),
                "重新生成失败"
            )
        }
    }

    private fun clientBaseUrl(): String =
        com.dbagent.plugin.settings.DbAgentSettings.getInstance().state.engineUrl

    /** 最终 SQL：引擎 DDL + 选中索引 ALTER TABLE ADD INDEX（主键固定项不重复生成）。 */
    private fun finalSql(): String {
        val sb = StringBuilder()
        sb.append(result.get("ddl")?.asString.orEmpty().trim().removeSuffix(";")).append(";\n")
        val tableName = tableNameField.text.trim()
        for ((check, index) in indexChecks) {
            if (check.isSelected && !index.fixed) {
                sb.append("ALTER TABLE `").append(tableName).append("` ADD INDEX `")
                    .append(index.name).append("` (")
                    .append(index.columns.joinToString(", ") { "`$it`" })
                    .append(");\n")
            }
        }
        return sb.toString()
    }

    private fun copySql() {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(finalSql()), null)
        notify("SQL 已复制到剪贴板", NotificationType.INFORMATION)
    }

    /** 插入当前编辑器光标处；无活动编辑器则新建 .sql 临时文件打开。 */
    private fun insertSql() {
        val sql = finalSql()
        val editor: Editor? = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor != null && !editor.isViewer) {
            WriteCommandAction.runWriteCommandAction(project) {
                editor.document.insertString(editor.caretModel.offset, sql)
            }
        } else {
            val file = LightVirtualFile(
                "dbagent_" + tableNameField.text.trim().ifEmpty { "ddl" } + ".sql",
                PlainTextFileType.INSTANCE, sql
            )
            FileEditorManager.getInstance(project).openFile(file, true)
        }
    }

    /** 完成并登记：复核通过定义回传 /confirm 落库（确认缓存级下次直接复用）。 */
    override fun doOKAction() {
        val payload = JsonObject()
        payload.addProperty("tableChineseName", originalForm.get("tableChineseName")?.asString.orEmpty())
        val fieldNames = JsonArray()
        val confirmed = JsonArray()
        val resultFields = result.getAsJsonArray("fields") ?: JsonArray()
        for (el in resultFields) {
            val f = el.asJsonObject
            val chineseName = f.get("chineseName")?.asString.orEmpty()
            fieldNames.add(chineseName)
            val row = rows.firstOrNull { it.chineseName == chineseName }
            val c = JsonObject()
            c.addProperty("chineseName", chineseName)
            c.addProperty("englishName", row?.englishName ?: f.get("englishName")?.asString.orEmpty())
            c.addProperty("physicalType", row?.physicalType ?: f.get("physicalType")?.asString.orEmpty())
            f.get("dataType")?.let { c.addProperty("dataType", it.asString) }
            f.getAsJsonArray("codeList")?.let { c.add("codeList", it) }
            confirmed.add(c)
        }
        payload.add("fieldNames", fieldNames)
        payload.add("confirmed", confirmed)
        try {
            val resp = client.confirm(payload)
            notify("已登记 " + (resp.get("confirmed")?.asInt ?: 0) + " 个字段定义（同上下文下次直接复用）",
                NotificationType.INFORMATION)
            super.doOKAction()
        } catch (t: Throwable) {
            Messages.showErrorDialog(
                project,
                EngineClient.friendlyMessage(t, clientBaseUrl()),
                "登记失败"
            )
        }
    }

    private fun notify(content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("DbAgent")
            .createNotification("DbAgent", content, type)
            .notify(project)
    }
}
