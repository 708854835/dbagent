package com.dbagent.plugin.wizard

import com.dbagent.plugin.settings.DbAgentSettings
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

/**
 * 建表向导对话框：连接设置（引擎地址/Token/NotesID，本地保存、Token 存 PasswordSafe）
 * + 表信息（中文名/描述）+ 字段表格（动态增删行）。
 * 校验分级：阻断级（表中文名空、字段中文名空、字段数超 30）阻止继续；
 * 警告级（名称超 15 汉字、含括号/斜杠/“和”“或”连接词）仅提示不阻断。
 */
class TableWizardDialog(project: Project) : DialogWrapper(project, true) {

    private val settings = DbAgentSettings.getInstance()

    private val engineUrlField = JBTextField(settings.state.engineUrl)
    private val tokenField = JBPasswordField().apply { text = settings.token }
    private val notesIdField = JBTextField(settings.state.notesId)
    private val tableNameField = JBTextField()
    private val descriptionField = JBTextField()

    private val warningLabel = JBLabel(" ").apply {
        foreground = com.intellij.ui.JBColor.ORANGE
    }

    private val model = object : DefaultTableModel(arrayOf("字段中文名 *", "必填", "主键", "长度"), 0) {
        override fun getColumnClass(column: Int): Class<*> = when (column) {
            1, 2 -> java.lang.Boolean::class.java
            else -> String::class.java
        }
    }
    private val table = JBTable(model)

    init {
        title = "新建数据库表（DbAgent 向导）"
        model.addRow(arrayOf("", false, false, ""))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val tabs = JBTabbedPane()
        tabs.addTab("表信息", buildMainPanel())
        tabs.addTab("连接设置", buildConnectionPanel())
        val root = JPanel(BorderLayout(0, JBUI.scale(8)))
        root.border = JBUI.Borders.empty(8)
        root.add(tabs, BorderLayout.CENTER)
        root.add(warningLabel, BorderLayout.SOUTH)
        return root
    }

    private fun buildMainPanel(): JComponent {
        val form = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
            weightx = 0.0
        }
        form.add(JBLabel("表中文名 *："), c)
        c.gridx = 1; c.weightx = 1.0; c.gridwidth = 2
        form.add(tableNameField, c)
        c.gridx = 0; c.gridy = 1; c.weightx = 0.0; c.gridwidth = 1
        form.add(JBLabel("表描述："), c)
        c.gridx = 1; c.weightx = 1.0; c.gridwidth = 2
        form.add(descriptionField, c)

        val addRow = JButton("添加行")
        addRow.addActionListener { model.addRow(arrayOf("", false, false, "")) }
        val removeRow = JButton("删除选中行")
        removeRow.addActionListener {
            val rows = table.selectedRows.sortedDescending()
            rows.forEach { if (model.rowCount > 1) model.removeRow(table.convertRowIndexToModel(it)) }
        }
        val buttons = JPanel()
        buttons.add(addRow)
        buttons.add(removeRow)

        c.gridx = 0; c.gridy = 2; c.gridwidth = 3; c.weightx = 1.0
        form.add(buttons, c)
        c.gridy = 3; c.weighty = 1.0; c.fill = GridBagConstraints.BOTH
        form.add(JBScrollPane(table), c)
        return form
    }

    private fun buildConnectionPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        panel.border = JBUI.Borders.empty(8)
        val c = GridBagConstraints().apply {
            fill = GridBagConstraints.HORIZONTAL
            insets = JBUI.insets(4)
        }
        fun addRow(label: String, field: JComponent, row: Int) {
            c.gridx = 0; c.gridy = row; c.weightx = 0.0
            panel.add(JBLabel(label), c)
            c.gridx = 1; c.weightx = 1.0
            panel.add(field, c)
        }
        addRow("引擎地址：", engineUrlField, 0)
        addRow("NotesID *：", notesIdField, 1)
        addRow("Token *：", tokenField, 2)
        c.gridx = 0; c.gridy = 3; c.gridwidth = 2
        panel.add(JBLabel("Token 仅存 IDE PasswordSafe，不落明文配置文件。"), c)
        return panel
    }

    /** 校验分级：阻断级返回 ValidationInfo 阻止 OK；警告级仅提示。 */
    override fun doValidate(): ValidationInfo? {
        val warnings = mutableListOf<String>()
        if (tableNameField.text.isBlank()) {
            warningLabel.text = " "
            return ValidationInfo("表中文名为空（阻断级）", tableNameField)
        }
        if (tableNameField.text.trim().length > 15) {
            warnings.add("表名超 15 汉字，建议精简")
        }
        if (tableNameField.text.contains(Regex("[()（）/\\\\]"))) {
            warnings.add("表名含括号/斜杠，引擎将忽略括号注释")
        }
        if (tableNameField.text.contains(Regex("[和或]"))) {
            warnings.add("表名含「和/或」连接词，疑似多主题混合，建议拆表")
        }
        if (notesIdField.text.isBlank()) {
            warningLabel.text = " "
            return ValidationInfo("NotesID 为空（阻断级）", notesIdField)
        }
        var blankField = -1
        for (i in 0 until model.rowCount) {
            val name = (model.getValueAt(i, 0) as? String)?.trim().orEmpty()
            if (name.isEmpty() && blankField < 0) blankField = i + 1
            if (name.length > 15) warnings.add("字段「$name」超 15 汉字，建议精简")
            if (name.contains(Regex("[()（）/\\\\]"))) warnings.add("字段「$name」含括号/斜杠")
            if (name.contains(Regex("[和或]"))) warnings.add("字段「$name」含「和/或」连接词，疑似混合定义")
        }
        if (blankField > 0) {
            warningLabel.text = " "
            return ValidationInfo("第 $blankField 行字段中文名为空（阻断级）", table)
        }
        // 字段数含自动补齐（自增 id + 2 审计字段）超 30 阻断
        if (model.rowCount + 3 > 30) {
            warningLabel.text = " "
            return ValidationInfo("字段总数（含自动补齐）超 30（阻断级），请拆表", table)
        }
        warningLabel.text = if (warnings.isEmpty()) " "
        else "警告：" + warnings.distinct().joinToString("；")
        return null
    }

    /** 组装引擎表单 JSON（owningSystem 由引擎按 Token 注入，插件不自称）。 */
    fun buildForm(): JsonObject {
        val form = JsonObject()
        form.addProperty("tableChineseName", tableNameField.text.trim())
        if (descriptionField.text.isNotBlank()) {
            form.addProperty("description", descriptionField.text.trim())
        }
        form.addProperty("notesId", notesIdField.text.trim())
        val fields = JsonArray()
        for (i in 0 until model.rowCount) {
            val f = JsonObject()
            f.addProperty("chineseName", (model.getValueAt(i, 0) as? String)?.trim().orEmpty())
            if (model.getValueAt(i, 1) == true) f.addProperty("required", true)
            if (model.getValueAt(i, 2) == true) f.addProperty("primaryKey", true)
            val length = (model.getValueAt(i, 3) as? String)?.trim().orEmpty()
            length.toIntOrNull()?.let { f.addProperty("length", it) }
            fields.add(f)
        }
        form.add("fields", fields)
        return form
    }

    /** OK 后持久化连接设置（Token 存 PasswordSafe）。 */
    fun saveSettings() {
        settings.state.engineUrl = engineUrlField.text.trim()
        settings.state.notesId = notesIdField.text.trim()
        settings.token = String(tokenField.password)
    }

    fun engineUrl(): String = engineUrlField.text.trim()
    fun token(): String = String(tokenField.password)
}
