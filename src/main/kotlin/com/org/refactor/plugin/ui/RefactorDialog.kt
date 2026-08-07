package com.org.refactor.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.org.refactor.plugin.conflict.ConflictDetector
import com.org.refactor.plugin.discovery.ComponentDiscoverer
import com.org.refactor.plugin.discovery.TypeAliasDiscoverer
import com.org.refactor.plugin.discovery.StringResourceDiscoverer
import com.org.refactor.plugin.model.ComponentInfo
import com.org.refactor.plugin.model.ConflictReport
import com.org.refactor.plugin.model.ModuleSelection
import com.org.refactor.plugin.model.ProjectIndex
import com.org.refactor.plugin.model.RefactorOptions
import com.org.refactor.plugin.model.RefactorPlan
import com.org.refactor.plugin.model.SymbolInfo
import com.org.refactor.plugin.model.TypeAliasInfo
import com.org.refactor.plugin.model.StringResourceInfo
import com.org.refactor.plugin.model.StringResourceRename
import com.org.refactor.plugin.model.ValueXmlFileGroup
import com.org.refactor.plugin.model.ValueXmlFileInfo
import com.org.refactor.plugin.plan.RefactorPlanGenerator
import com.org.refactor.plugin.plan.ValueXmlSelection
import com.org.refactor.plugin.psi.UniversalSymbolCollector
import com.org.refactor.plugin.references.DependencyGraph
import com.org.refactor.plugin.scanner.ProjectScanner
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.JTable
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

class RefactorDialog(private val project: Project) : DialogWrapper(project) {

    private var projectIndex: ProjectIndex? = null
    private var scanDebug: ProjectScanner.ScanDebug? = null
    private var components: List<ComponentInfo> = emptyList()
    private var symbols: List<SymbolInfo> = emptyList()
    private var typeAliases: List<TypeAliasInfo> = emptyList()
    private var stringResources: List<StringResourceInfo> = emptyList()
    private var valueXmlFiles: List<ValueXmlFileInfo> = emptyList()

    var refactorPlan: RefactorPlan? = null
        private set
    var conflictReport: ConflictReport? = null
        private set

    private val suffixCombo = JComboBox(arrayOf("Ref", "V2", "New", "FeatureA")).apply {
        isEditable = true
        preferredSize = Dimension(190, preferredSize.height)
    }
    private val removeSuffixField = JTextField(18)
    private val moduleChoices: List<ModuleChoice> = buildModuleChoices()
    private val moduleList = JList(moduleChoices.toTypedArray()).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        visibleRowCount = minOf(7, model.size)
        selectedIndex = 0
    }
    private var adjustingModuleSelection = false
    private var previousModuleSelection: Set<Int> = setOf(0)
    private val refactorClasses = JCheckBox("Refactor classes", true)
    private val refactorFunctions = JCheckBox("Refactor functions", false)
    private val refactorVariables = JCheckBox("Refactor variables", false)
    private val refactorTypeAliases = JCheckBox("Refactor typealiases", true)
    private val refactorStrings = JCheckBox("Refactor strings", true)
    private val refactorColors = JCheckBox("Refactor colors", true)
    private val refactorStyles = JCheckBox("Refactor styles", true)
    private val refactorDrawables = JCheckBox("Refactor drawables", true)
    private val refactorLayouts = JCheckBox("Refactor layouts", true)
    private val shuffleFunctions = JCheckBox("Shuffle functions", false)
    private val shuffleVariables = JCheckBox("Shuffle variables", false)

    private val mainPanel = JPanel(BorderLayout(0, 12))
    private val cardPanel = JPanel(java.awt.CardLayout())
    private val summaryLabel = JLabel("Ready to scan")

    init {
        title = "Project Refactor"
        init()
        isOKActionEnabled = false
    }

    override fun createCenterPanel(): JComponent = mainPanel.apply {
        preferredSize = Dimension(1080, 720)
        border = BorderFactory.createEmptyBorder(10, 12, 10, 12)
        add(createTopPanel(), BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)
        showStartStep()
    }

    private fun createTopPanel(): JComponent {
        val modulePanel = JPanel(BorderLayout(0, 6)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Target modules"),
                BorderFactory.createEmptyBorder(4, 8, 8, 8),
            )
            add(JLabel("Use Ctrl/Shift to select multiple modules."), BorderLayout.NORTH)
            add(JBScrollPane(moduleList).apply { preferredSize = Dimension(440, 125) }, BorderLayout.CENTER)
        }

        val namingPanel = JPanel(GridBagLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Naming"),
                BorderFactory.createEmptyBorder(8, 10, 8, 10),
            )
            val constraints = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(4, 4, 8, 8)
            }
            constraints.gridx = 0
            constraints.gridy = 0
            constraints.weightx = 0.0
            add(JLabel("Suffix to add"), constraints)
            constraints.gridx = 1
            constraints.weightx = 1.0
            add(suffixCombo, constraints)
            constraints.gridx = 0
            constraints.gridy = 1
            constraints.weightx = 0.0
            add(JLabel("Text to remove"), constraints)
            constraints.gridx = 1
            constraints.weightx = 1.0
            add(removeSuffixField, constraints)
            constraints.gridx = 0
            constraints.gridy = 2
            constraints.gridwidth = 2
            add(JLabel("Resource suffixes are converted to lowercase automatically."), constraints)
        }

        val optionsPanel = JPanel(GridLayout(4, 3, 14, 6)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Operations"),
                BorderFactory.createEmptyBorder(6, 10, 8, 10),
            )
            add(refactorClasses)
            add(refactorFunctions)
            add(refactorVariables)
            add(refactorTypeAliases)
            add(refactorStrings)
            add(refactorColors)
            add(refactorStyles)
            add(refactorDrawables)
            add(refactorLayouts)
            add(shuffleFunctions)
            add(shuffleVariables)
        }

        val scanButton = JButton("Scan Project").apply {
            preferredSize = Dimension(150, 34)
            addActionListener { runScan() }
        }
        val selectionPanel = JPanel(GridLayout(1, 2, 12, 0)).apply {
            add(modulePanel)
            add(namingPanel)
        }
        val statusPanel = JPanel(BorderLayout(12, 0)).apply {
            border = BorderFactory.createEmptyBorder(6, 2, 0, 2)
            add(summaryLabel, BorderLayout.CENTER)
            add(scanButton, BorderLayout.EAST)
        }
        val panel = JPanel(BorderLayout(0, 10)).apply {
            add(selectionPanel, BorderLayout.NORTH)
            add(optionsPanel, BorderLayout.CENTER)
            add(statusPanel, BorderLayout.SOUTH)
        }

        suffixCombo.addActionListener { invalidatePlan() }
        moduleList.addListSelectionListener { event ->
            if (event.valueIsAdjusting || adjustingModuleSelection) return@addListSelectionListener
            adjustingModuleSelection = true
            val current = moduleList.selectedIndices.toSet()
            when {
                0 in current && current.size > 1 && 0 !in previousModuleSelection -> {
                    moduleList.selectedIndex = 0
                }
                0 in current && current.size > 1 -> {
                    moduleList.removeSelectionInterval(0, 0)
                }
            }
            previousModuleSelection = moduleList.selectedIndices.toSet()
            adjustingModuleSelection = false
            invalidatePlan()
        }
        removeSuffixField.document.addDocumentListener(SimpleDocumentListener(::invalidatePlan))
        listOf(
            refactorClasses, refactorFunctions, refactorVariables, refactorTypeAliases, refactorStrings,
            refactorColors, refactorStyles,
            refactorDrawables, refactorLayouts, shuffleFunctions, shuffleVariables,
        )
            .forEach { it.addActionListener { invalidatePlan() } }
        return panel
    }

    private fun showStartStep() {
        cardPanel.removeAll()
        cardPanel.add(
            JLabel("1) Configure  •  2) Scan Project  •  3) Review preview  •  4) Click OK", JLabel.CENTER),
            "start",
        )
        cardPanel.revalidate()
        cardPanel.repaint()
    }

    private fun showPreview() {
        val plan = refactorPlan ?: return
        cardPanel.removeAll()
        val tabs = JTabbedPane()

        val classes = plan.componentRenames
        if (classes.isNotEmpty()) {
            val data = classes.map { arrayOf(it.componentType.name, it.oldName, it.newName, shortPath(it.sourceFile)) }
            tabs.addTab(
                "Classes (${classes.size})",
                previewTable(data, arrayOf("Type", "Old", "New", "File")),
            )
        } else {
            tabs.addTab("Classes (0)", JLabel("No class rename selected or required"))
        }

        val symbols = plan.symbolRenames
        if (symbols.isNotEmpty()) {
            val data = symbols.map { arrayOf(it.kind.name, it.oldName, it.newName, shortPath(it.sourceFile)) }
            tabs.addTab(
                "Symbols (${symbols.size})",
                previewTable(data, arrayOf("Kind", "Old", "New", "File")),
            )
        } else {
            tabs.addTab("Symbols (0)", JLabel("No function or variable rename selected or required"))
        }

        val aliases = plan.typeAliasRenames
        if (aliases.isNotEmpty()) {
            val data = aliases.map { rename ->
                arrayOf(
                    rename.oldName,
                    rename.newName,
                    shortPath(rename.sourceFile),
                    rename.skipReason ?: "Ready",
                )
            }
            tabs.addTab(
                "Typealiases (${aliases.count { it.checked }}/${aliases.size})",
                previewTable(data, arrayOf("Old", "New", "File", "Status")),
            )
        } else {
            tabs.addTab("Typealiases (0)", JLabel("No typealias rename selected or required"))
        }

        val resources = plan.resourceRenames
        if (resources.isNotEmpty()) {
            val data = resources.map { rename ->
                arrayOf(
                    rename.type.name,
                    rename.oldName,
                    rename.newName,
                    ModuleSelection.shortDisplayName(rename.moduleName),
                    rename.variants.size.toString(),
                    rename.skipReason ?: "Ready",
                )
            }
            tabs.addTab(
                "Resources (${resources.count { it.checked }}/${resources.size})",
                previewTable(data, arrayOf("Type", "Old", "New", "Module", "Variants", "Status")),
            )
        } else {
            tabs.addTab("Resources (0)", JLabel("No drawable or layout rename selected or required"))
        }

        var valueResourceModel: StringResourceTableModel? = null
        var valuesTabIndex = -1
        val valueFiles = plan.valueXmlFileGroups
        if (valueFiles.isNotEmpty()) {
            val fileTabIndex = tabs.tabCount
            val model = ValueXmlFileTableModel(valueFiles) { updatedGroups ->
                refactorPlan?.let { current ->
                    val updatedPlan = ValueXmlSelection.apply(current, updatedGroups)
                    refactorPlan = updatedPlan
                    tabs.setTitleAt(
                        fileTabIndex,
                        "Values XML (${updatedGroups.count { it.checked }}/${updatedGroups.size})",
                    )
                    valueResourceModel?.replaceRows(updatedPlan.stringResourceRenames)
                    if (valuesTabIndex >= 0) {
                        tabs.setTitleAt(
                            valuesTabIndex,
                            "Values (${updatedPlan.stringResourceRenames.count { it.checked }}/" +
                                "${updatedPlan.stringResourceRenames.size})",
                        )
                    }
                }
            }
            tabs.addTab(
                "Values XML (${valueFiles.count { it.checked }}/${valueFiles.size})",
                JBScrollPane(JTable(model).apply {
                    rowHeight = 24
                    fillsViewportHeight = true
                    autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
                    setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
                }),
            )
        } else {
            tabs.addTab("Values XML (0)", JLabel("No res/values* XML file available"))
        }

        val strings = plan.stringResourceRenames
        if (strings.isNotEmpty()) {
            valuesTabIndex = tabs.tabCount
            val model = StringResourceTableModel(strings) { updated ->
                refactorPlan?.let { current ->
                    refactorPlan = current.copy(stringResourceRenames = updated)
                    tabs.setTitleAt(
                        valuesTabIndex,
                        "Values (${updated.count { it.checked }}/${updated.size})",
                    )
                }
            }
            valueResourceModel = model
            tabs.addTab(
                "Values (${strings.count { it.checked }}/${strings.size})",
                JBScrollPane(JTable(model).apply {
                    rowHeight = 24
                    fillsViewportHeight = true
                    autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
                    setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
                }),
            )
        } else {
            tabs.addTab("Values (0)", JLabel("No string, color, or style rename available"))
        }

        val report = conflictReport
        if (report != null && report.conflicts.isNotEmpty()) {
            val text = report.conflicts.joinToString("\n") { "${it.severity}: ${it.message}" }
            tabs.addTab(
                "Conflicts (${report.conflicts.size})",
                JBScrollPane(JTextArea(text).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }),
            )
        } else {
            tabs.addTab("Conflicts", JLabel("No conflicts detected"))
        }

        if (plan.options.hasShuffleOperation) {
            val kinds = buildList {
                if (plan.options.shuffleFunctions) add("functions")
                if (plan.options.shuffleVariables) add("variables")
            }.joinToString(" and ")
            tabs.addTab(
                "Shuffle (${plan.shuffleFilePaths.size})",
                JLabel("Shuffle $kinds in ${plan.shuffleFilePaths.size} Kotlin source file(s)"),
            )
        }

        cardPanel.add(JPanel(BorderLayout()).apply { add(tabs, BorderLayout.CENTER) }, "preview")
        cardPanel.revalidate()
        cardPanel.repaint()
    }

    private fun previewTable(data: List<Array<String>>, columns: Array<String>): JComponent =
        JBScrollPane(
            JTable(data.toTypedArray(), columns).apply {
                rowHeight = 24
                fillsViewportHeight = true
                autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN
                setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            },
        )

    private fun runScan() {
        val options = currentOptions()
        val error = validate(options)
        if (error != null) {
            Messages.showErrorDialog(project, error, "Invalid Refactor Options")
            return
        }

        refactorPlan = null
        conflictReport = null
        summaryLabel.text = "Scanning..."
        isOKActionEnabled = false

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Scanning Project...", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Scanning Kotlin sources..."
                    val scanner = ProjectScanner(project)
                    val index = scanner.scan(options.selectedModuleNames)
                    scanDebug = scanner.debug
                    projectIndex = index

                    indicator.text = "Discovering classes..."
                    components = if (options.refactorClasses) {
                        ComponentDiscoverer(project).discover(index)
                    } else {
                        emptyList()
                    }

                    indicator.text = "Collecting functions and variables..."
                    symbols = UniversalSymbolCollector(project).collectAll(index, options)

                    indicator.text = "Collecting typealiases..."
                    typeAliases = if (options.refactorTypeAliases) {
                        TypeAliasDiscoverer(project).discover(index)
                    } else {
                        emptyList()
                    }

                    indicator.text = "Collecting string resources..."
                    stringResources = if (options.refactorStrings || options.refactorColors || options.refactorStyles) {
                        StringResourceDiscoverer(project).discover(index)
                    } else {
                        emptyList()
                    }
                    valueXmlFiles = if (options.refactorStrings || options.refactorColors || options.refactorStyles) {
                        StringResourceDiscoverer(project).discoverFiles(index)
                    } else {
                        emptyList()
                    }

                    indicator.text = "Generating plan..."
                    refactorPlan = RefactorPlanGenerator(options).generate(
                        components,
                        symbols,
                        index.allKotlinFiles.map { it.absolutePath },
                        index.androidResourceFiles,
                        typeAliases,
                        stringResources,
                        valueXmlFiles,
                    )

                    // Preview deliberately avoids project-wide ReferencesSearch. Usage discovery
                    // happens once during execution; static conflicts are enough to build preview.
                    val graph = DependencyGraph()
                    symbols.forEach { graph.addSymbol(it) }

                    indicator.text = "Detecting conflicts..."
                    conflictReport = ConflictDetector(project).detect(requireNotNull(refactorPlan), graph, index)
                } catch (e: Exception) {
                    refactorPlan = null
                    conflictReport = null
                    scanDebug = null
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "Scan failed: ${e.message}", "Error")
                    }
                }
            }

            override fun onFinished() {
                val plan = refactorPlan
                if (plan != null) {
                    val conflicts = conflictReport?.conflicts?.size ?: 0
                    summaryLabel.text = buildString {
                        append("Classes: ${plan.totalClasses} | Symbols: ${plan.totalSymbols}")
                        append(" | Typealiases: ${plan.typeAliasRenames.count { it.checked }}")
                        append(" | Resources: ${plan.resourceRenames.count { it.checked }}")
                        append(" | Values: ${plan.stringResourceRenames.count { it.checked }}/${plan.stringResourceRenames.size}")
                        val modules = plan.options.selectedModuleNames
                            ?.map(ModuleSelection::shortDisplayName)
                            ?.sorted()
                            ?.joinToString(", ")
                            ?: "All"
                        append(" | Modules: $modules")
                        append(" | Kotlin files: ${scanDebug?.kotlinCount ?: 0}")
                        append(" | Shuffle files: ${plan.shuffleFilePaths.size} | Conflicts: $conflicts")
                    }
                } else {
                    summaryLabel.text = "Scan failed"
                }
                isOKActionEnabled = plan != null && conflictReport?.isSafe != false
                showPreview()
            }
        })
    }

    private fun currentOptions(): RefactorOptions = RefactorOptions(
        suffixToAdd = suffixCombo.editor.item?.toString()?.trim().orEmpty(),
        suffixToRemove = removeSuffixField.text.trim(),
        selectedModuleNames = moduleList.selectedValuesList
            .takeUnless { choices -> choices.any { it.logicalName == null } }
            ?.mapNotNull { it.logicalName }
            ?.toSet(),
        refactorClasses = refactorClasses.isSelected,
        refactorFunctions = refactorFunctions.isSelected,
        refactorVariables = refactorVariables.isSelected,
        refactorTypeAliases = refactorTypeAliases.isSelected,
        refactorStrings = refactorStrings.isSelected,
        refactorColors = refactorColors.isSelected,
        refactorStyles = refactorStyles.isSelected,
        refactorDrawables = refactorDrawables.isSelected,
        refactorLayouts = refactorLayouts.isSelected,
        shuffleFunctions = shuffleFunctions.isSelected,
        shuffleVariables = shuffleVariables.isSelected,
    )

    private fun validate(options: RefactorOptions): String? {
        if (!options.hasAnyOperation) return "Select at least one refactor or shuffle operation."
        if (options.selectedModuleNames?.isEmpty() == true) return "Select at least one module."
        if (options.hasRefactorOperation && options.suffixToAdd.isEmpty()) {
            return "Suffix to add is required when a refactor option is selected."
        }
        val validSuffix = Regex("[A-Za-z0-9_]+")
        if (options.suffixToAdd.isNotEmpty() && !validSuffix.matches(options.suffixToAdd)) {
            return "Suffix to add may contain only letters, digits, and underscores."
        }
        if (options.suffixToRemove.isNotEmpty() && !validSuffix.matches(options.suffixToRemove)) {
            return "Text to remove may contain only letters, digits, and underscores."
        }
        return null
    }

    private fun invalidatePlan() {
        refactorPlan = null
        conflictReport = null
        isOKActionEnabled = false
        summaryLabel.text = "Options changed - scan again"
        showStartStep()
    }

    private fun shortPath(path: String): String = path.replace('\\', '/').substringAfterLast('/')

    private fun buildModuleChoices(): List<ModuleChoice> {
        val logicalNames = ModuleManager.getInstance(project).modules
            .asSequence()
            .filter { ModuleRootManager.getInstance(it).contentRoots.isNotEmpty() }
            .map { ModuleSelection.logicalName(it.name) }
            .distinct()
            .sorted()
            .toList()
        val shortNameCounts = logicalNames.groupingBy(ModuleSelection::shortDisplayName).eachCount()
        return listOf(ModuleChoice(null, ALL_MODULES)) + logicalNames.map { logicalName ->
            val shortName = ModuleSelection.shortDisplayName(logicalName)
            val label = if (shortNameCounts[shortName] == 1) shortName else logicalName
            ModuleChoice(logicalName, label)
        }
    }

    private companion object {
        const val ALL_MODULES = "All modules"
    }
}

private data class ModuleChoice(val logicalName: String?, val label: String) {
    override fun toString(): String = label
}

private class SimpleDocumentListener(private val onChange: () -> Unit) : javax.swing.event.DocumentListener {
    override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
    override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
    override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = onChange()
}

private class StringResourceTableModel(
    renames: List<StringResourceRename>,
    private val onSelectionChanged: (List<StringResourceRename>) -> Unit,
) : AbstractTableModel() {
    private val rows = renames.toMutableList()
    private val columns = arrayOf("Selected", "Type", "Old", "New", "Module", "Locales", "Status")

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]
    override fun getColumnClass(columnIndex: Int): Class<*> =
        if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean =
        columnIndex == 0 && rows[rowIndex].selectable

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val rename = rows[rowIndex]
        return when (columnIndex) {
            0 -> rename.checked
            1 -> rename.type.name
            2 -> rename.oldName
            3 -> rename.newName
            4 -> ModuleSelection.shortDisplayName(rename.moduleName)
            5 -> rename.variants.size.toString()
            6 -> rename.skipReason ?: if (rename.checked) "Selected" else "Not selected"
            else -> ""
        }
    }

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        if (!isCellEditable(rowIndex, columnIndex)) return
        rows[rowIndex] = rows[rowIndex].copy(checked = value == true)
        fireTableRowsUpdated(rowIndex, rowIndex)
        onSelectionChanged(rows.toList())
    }

    fun replaceRows(updated: List<StringResourceRename>) {
        rows.clear()
        rows.addAll(updated)
        fireTableDataChanged()
    }
}

private class ValueXmlFileTableModel(
    groups: List<ValueXmlFileGroup>,
    private val onSelectionChanged: (List<ValueXmlFileGroup>) -> Unit,
) : AbstractTableModel() {
    private val rows = groups.toMutableList()
    private val columns = arrayOf("Selected", "File", "Module", "Variants", "Status")

    override fun getRowCount(): Int = rows.size
    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(column: Int): String = columns[column]
    override fun getColumnClass(columnIndex: Int): Class<*> =
        if (columnIndex == 0) java.lang.Boolean::class.java else String::class.java

    override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 0

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val group = rows[rowIndex]
        return when (columnIndex) {
            0 -> group.checked
            1 -> group.fileName
            2 -> ModuleSelection.shortDisplayName(group.moduleName)
            3 -> group.variants.size.toString()
            4 -> if (group.variants.any { !it.isWritable }) "Contains read-only variant" else "Ready"
            else -> ""
        }
    }

    override fun setValueAt(value: Any?, rowIndex: Int, columnIndex: Int) {
        if (!isCellEditable(rowIndex, columnIndex)) return
        rows[rowIndex] = rows[rowIndex].copy(checked = value == true)
        fireTableRowsUpdated(rowIndex, rowIndex)
        onSelectionChanged(rows.toList())
    }
}
