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
import com.org.refactor.plugin.model.ComponentInfo
import com.org.refactor.plugin.model.ConflictReport
import com.org.refactor.plugin.model.ModuleSelection
import com.org.refactor.plugin.model.ProjectIndex
import com.org.refactor.plugin.model.RefactorOptions
import com.org.refactor.plugin.model.RefactorPlan
import com.org.refactor.plugin.model.SymbolInfo
import com.org.refactor.plugin.plan.RefactorPlanGenerator
import com.org.refactor.plugin.psi.UniversalSymbolCollector
import com.org.refactor.plugin.references.DependencyGraph
import com.org.refactor.plugin.scanner.ProjectScanner
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
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

class RefactorDialog(private val project: Project) : DialogWrapper(project) {

    private var projectIndex: ProjectIndex? = null
    private var scanDebug: ProjectScanner.ScanDebug? = null
    private var components: List<ComponentInfo> = emptyList()
    private var symbols: List<SymbolInfo> = emptyList()

    var refactorPlan: RefactorPlan? = null
        private set
    var conflictReport: ConflictReport? = null
        private set

    private val suffixCombo = JComboBox(arrayOf("Ref", "V2", "New", "FeatureA")).apply {
        isEditable = true
    }
    private val removeSuffixField = JTextField(10)
    private val moduleChoices: List<ModuleChoice> = buildModuleChoices()
    private val moduleList = JList(moduleChoices.toTypedArray()).apply {
        selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION
        visibleRowCount = minOf(4, model.size)
        selectedIndex = 0
    }
    private var adjustingModuleSelection = false
    private var previousModuleSelection: Set<Int> = setOf(0)
    private val refactorClasses = JCheckBox("Refactor classes", true)
    private val refactorFunctions = JCheckBox("Refactor functions", false)
    private val refactorVariables = JCheckBox("Refactor variables", false)
    private val shuffleFunctions = JCheckBox("Shuffle functions", false)
    private val shuffleVariables = JCheckBox("Shuffle variables", false)

    private val mainPanel = JPanel(BorderLayout())
    private val cardPanel = JPanel(java.awt.CardLayout())
    private val summaryLabel = JLabel("Ready to scan")

    init {
        title = "Project Refactor"
        init()
        isOKActionEnabled = false
    }

    override fun createCenterPanel(): JComponent = mainPanel.apply {
        preferredSize = java.awt.Dimension(850, 580)
        add(createTopPanel(), BorderLayout.NORTH)
        add(cardPanel, BorderLayout.CENTER)
        showStartStep()
    }

    private fun createTopPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        val namesPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(JLabel("Modules (Ctrl/Shift):"))
            add(JBScrollPane(moduleList).apply { preferredSize = Dimension(220, 72) })
            add(JLabel("Suffix to add:"))
            add(suffixCombo)
            add(JLabel("Existing suffix to remove:"))
            add(removeSuffixField)
        }
        val optionsPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(refactorClasses)
            add(refactorFunctions)
            add(refactorVariables)
            add(shuffleFunctions)
            add(shuffleVariables)
            val scanButton = JButton("Scan Project")
            scanButton.addActionListener { runScan() }
            add(scanButton)
        }
        val controls = JPanel(BorderLayout()).apply {
            add(namesPanel, BorderLayout.NORTH)
            add(optionsPanel, BorderLayout.SOUTH)
        }
        panel.add(controls, BorderLayout.NORTH)
        panel.add(summaryLabel, BorderLayout.SOUTH)

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
        listOf(refactorClasses, refactorFunctions, refactorVariables, shuffleFunctions, shuffleVariables)
            .forEach { it.addActionListener { invalidatePlan() } }
        return panel
    }

    private fun showStartStep() {
        cardPanel.removeAll()
        cardPanel.add(
            JLabel("  1) Configure operations  2) Scan Project  3) Review preview  4) Click OK"),
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
                JBScrollPane(JTable(data.toTypedArray(), arrayOf("Type", "Old", "New", "File"))),
            )
        } else {
            tabs.addTab("Classes (0)", JLabel("No class rename selected or required"))
        }

        val symbols = plan.symbolRenames
        if (symbols.isNotEmpty()) {
            val data = symbols.map { arrayOf(it.kind.name, it.oldName, it.newName, shortPath(it.sourceFile)) }
            tabs.addTab(
                "Symbols (${symbols.size})",
                JBScrollPane(JTable(data.toTypedArray(), arrayOf("Kind", "Old", "New", "File"))),
            )
        } else {
            tabs.addTab("Symbols (0)", JLabel("No function or variable rename selected or required"))
        }

        val report = conflictReport
        if (report != null && report.conflicts.isNotEmpty()) {
            val text = report.conflicts.joinToString("\n") { "${it.severity}: ${it.message}" }
            tabs.addTab("Conflicts (${report.conflicts.size})", JBScrollPane(JTextArea(text)))
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

                    indicator.text = "Generating plan..."
                    refactorPlan = RefactorPlanGenerator(options).generate(
                        components,
                        symbols,
                        index.allKotlinFiles.map { it.absolutePath },
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
            return "Existing suffix may contain only letters, digits, and underscores."
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
