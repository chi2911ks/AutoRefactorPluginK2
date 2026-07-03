package com.org.refactor.plugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBScrollPane
import com.org.refactor.plugin.model.*
import com.org.refactor.plugin.scanner.ProjectScanner
import com.org.refactor.plugin.discovery.ComponentDiscoverer
import com.org.refactor.plugin.psi.UniversalSymbolCollector
import com.org.refactor.plugin.plan.RefactorPlanGenerator
import com.org.refactor.plugin.conflict.ConflictDetector
import com.org.refactor.plugin.references.DependencyGraph
import com.org.refactor.plugin.references.ReferenceResolver
import java.awt.BorderLayout
import javax.swing.*
import javax.swing.table.AbstractTableModel

class RefactorDialog(private val project: Project) : DialogWrapper(project) {

    private var suffix: String = "Ref"
    private var projectIndex: ProjectIndex? = null
    private var components: List<ComponentInfo> = emptyList()
    private var symbols: List<SymbolInfo> = emptyList()
    var refactorPlan: RefactorPlan? = null
        private set
    var conflictReport: ConflictReport? = null
        private set

    private val mainPanel = JPanel(BorderLayout())
    private val cardPanel = JPanel(java.awt.CardLayout())
    private val summaryLabel = JLabel("Ready to scan")

    init {
        title = "Android Refactor"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return mainPanel.apply {
            preferredSize = java.awt.Dimension(750, 520)
            add(createTopPanel(), BorderLayout.NORTH)
            add(cardPanel, BorderLayout.CENTER)
            showSuffixStep()
        }
    }

    private fun createTopPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        val btnPanel = JPanel()

        val suffixCombo = JComboBox(arrayOf("Ref", "V2", "New", "FeatureA"))
        suffixCombo.isEditable = true
        suffixCombo.addActionListener { suffix = suffixCombo.selectedItem.toString() }
        btnPanel.add(JLabel("Suffix: "))
        btnPanel.add(suffixCombo)

        val scanButton = JButton("Scan Project")
        scanButton.addActionListener {
            runScan()
        }
        btnPanel.add(scanButton)

        panel.add(btnPanel, BorderLayout.NORTH)
        panel.add(summaryLabel, BorderLayout.SOUTH)
        return panel
    }

    private fun showSuffixStep() {
        cardPanel.removeAll()
        val panel = JPanel(BorderLayout())
        panel.add(JLabel("  1) Choose suffix  2) Click Scan Project  3) Review tabs  4) Click OK to execute"), BorderLayout.CENTER)
        cardPanel.add(panel, "suffix")
        cardPanel.revalidate()
        cardPanel.repaint()
    }

    private fun showPreview() {
        val plan = refactorPlan ?: return
        cardPanel.removeAll()

        val panel = JPanel(BorderLayout())
        val tabs = JTabbedPane()

        // Classes tab
        val classNames = plan.componentRenames
        if (classNames.isNotEmpty()) {
            val data = classNames.map { arrayOf(it.componentType.name, it.oldName, it.newName) }
            val table = JTable(data.toTypedArray(), arrayOf("Type", "Old", "New"))
            tabs.addTab("Classes (${classNames.size})", JBScrollPane(table))
        } else {
            tabs.addTab("Classes (0)", JLabel("No Android UI components found"))
        }

        // Symbols tab
        if (plan.symbolRenames.isNotEmpty()) {
            val data = plan.symbolRenames.map {
                arrayOf(it.kind.name, it.oldName, it.newName, it.sourceFile.substringAfterLast('/'))
            }
            val table = JTable(data.toTypedArray(), arrayOf("Kind", "Old", "New", "File"))
            tabs.addTab("Symbols (${plan.symbolRenames.size})", JBScrollPane(table))
        }

        // Conflicts tab
        val report = conflictReport
        if (report != null && report.conflicts.isNotEmpty()) {
            val text = report.conflicts.joinToString("\n") { "${it.severity}: ${it.message}" }
            tabs.addTab("Conflicts", JBScrollPane(JTextArea(text)))
        } else {
            tabs.addTab("Conflicts", JLabel("No conflicts detected"))
        }

        panel.add(tabs, BorderLayout.CENTER)
        cardPanel.add(panel, "preview")
        cardPanel.revalidate()
        cardPanel.repaint()
    }

    private fun runScan() {
        summaryLabel.text = "Scanning..."
        isOKActionEnabled = false

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Scanning Project...", false) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Scanning files..."
                    val scanner = ProjectScanner(project)
                    projectIndex = scanner.scan()

                    indicator.text = "Discovering components..."
                    val discoverer = ComponentDiscoverer(project)
                    components = discoverer.discover(projectIndex!!)

                    indicator.text = "Collecting symbols..."
                    val collector = UniversalSymbolCollector(project)
                    symbols = collector.collectAll(components)

                    indicator.text = "Generating plan..."
                    val generator = RefactorPlanGenerator(suffix)
                    refactorPlan = generator.generate(components, symbols)

                    indicator.text = "Analyzing references..."
                    val resolver = ReferenceResolver(project)
                    val graph = DependencyGraph()
                    symbols.forEach { graph.addSymbol(it) }
                    for (symbol in symbols) {
                        val refs = resolver.resolveReferences(symbol)
                        refs.forEach { graph.addReference(symbol.fqn, it) }
                    }

                    indicator.text = "Detecting conflicts..."
                    val detector = ConflictDetector(project)
                    conflictReport = detector.detect(refactorPlan!!, graph, projectIndex!!)
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showErrorDialog(project, "Scan failed: ${e.message}", "Error")
                    }
                }
            }

            override fun onFinished() {
                val plan = refactorPlan
                if (plan != null) {
                    summaryLabel.text = "Classes: ${plan.totalClasses} | Symbols: ${plan.totalSymbols} | Files: ${plan.fileRenames.size} | Suffix: ${plan.suffix}"
                }
                isOKActionEnabled = refactorPlan != null
                showPreview()
            }
        })
    }
}
