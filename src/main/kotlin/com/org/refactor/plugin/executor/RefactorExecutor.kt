package com.org.refactor.plugin.executor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.usageView.UsageInfo
import com.org.refactor.plugin.model.*
import com.org.refactor.plugin.psi.K2Analysis
import org.jetbrains.kotlin.asJava.LightClassUtil
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedDeclaration
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

class RefactorExecutor(private val project: Project) {

    private data class Rep(val file: String, val start: Int, val end: Int)
    private data class SymbolTarget(
        val rename: SymbolRename,
        val pointer: SmartPsiElementPointer<PsiNamedElement>?,
    )
    private data class ClassTarget(
        val rename: ComponentRename,
        val pointer: SmartPsiElementPointer<PsiNamedElement>?,
    )
    private data class TypeAliasTarget(
        val rename: TypeAliasRename,
        val pointer: SmartPsiElementPointer<KtTypeAlias>?,
    )

    data class ExecutionResult(
        val success: Boolean, val classesRenamed: Int, val symbolsRenamed: Int,
        val referencesUpdated: Int, val filesRenamed: Int,
        val drawablesRenamed: Int = 0, val layoutsRenamed: Int = 0,
        val typeAliasesRenamed: Int = 0,
        val stringsRenamed: Int = 0,
        val errors: List<String>, val warnings: List<String>, val durationMs: Long,
        val classRenameBatches: Int = 0,
    )

    fun execute(plan: RefactorPlan): ExecutionResult {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return executeOnEdt(plan)

        var result: ExecutionResult? = null
        application.invokeAndWait {
            result = executeOnEdt(plan)
        }
        return requireNotNull(result)
    }

    /**
     * PSI rename processors and document mutations require IntelliJ's EDT write-intent context.
     * The action may call this executor from a background progress task, so all model access in
     * the mutation phase is marshalled to EDT as one operation.
     */
    private fun executeOnEdt(plan: RefactorPlan): ExecutionResult {
        val startTime = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var classesRenamed = 0; var symbolsRenamed = 0; var filesRenamed = 0
        var typeAliasesRenamed = 0
        var stringsRenamed = 0
        var referencesUpdated = 0; var drawablesRenamed = 0; var layoutsRenamed = 0
        var classRenameBatches = 0
        val symbolLog = mutableListOf<String>()   // per-symbol diagnostic trace

        val unchecked = plan.componentRenames.filter { it.checked }
        val classTargets = createClassTargets(unchecked)
        val symbolTargets = createSymbolTargets(plan.symbolRenames.filter { it.checked })
        val typeAliasTargets = createTypeAliasTargets(plan.typeAliasRenames.filter { it.checked })

        val overrideIndex = buildKotlinOverrideIndex(
            symbolTargets.asSequence()
                .filter { it.rename.kind == SymbolKind.FUNCTION }
                .map { it.rename.oldName }
                .toSet(),
        )
        for (target in typeAliasTargets) {
            val rename = target.rename
            try {
                val declaration = target.pointer?.element
                    ?: throw IllegalStateException("Typealias declaration not found: ${rename.fqn}")
                renameTypeAlias(declaration, rename.newName)
                typeAliasesRenamed++
            } catch (e: Exception) {
                errors.add("${rename.oldName}: ${e.message}")
            }
        }
        for (target in symbolTargets) {
            val symbol = target.rename
            try {
                val ok = renameSymbol(symbol, target.pointer?.element, symbolLog, overrideIndex)
                if (ok) symbolsRenamed++
            } catch (e: Exception) {
                errors.add("${symbol.oldName}: ${e.message}")
                symbolLog.add("FAIL ${symbol.oldName}->${symbol.newName}: ${e.message}")
            }
        }

        // Rename class files before the batch PSI operation. Multi-element IntelliJ rename can
        // automatically rename only the first related file, leaving later old-path handles stale.
        for (r in plan.fileRenames) {
            try {
                if (renameFile(r)) filesRenamed++
            } catch (_: Exception) {}
        }

        val classRequests = classTargets
            .sortedByDescending { it.rename.fqn.count { char -> char == '.' } }
            .mapNotNull { target ->
                val rename = target.rename
                val declaration = target.pointer?.element
                if (declaration == null) {
                    errors.add("${rename.oldName}: Declaration not found: ${rename.fqn}")
                    null
                } else {
                    ClassRenameBatch.Request(declaration, rename.newName)
                }
            }
        if (classRequests.isNotEmpty()) {
            val batchResult = ClassRenameBatch(project).execute(classRequests)
            if (batchResult.success) {
                classesRenamed += batchResult.renamed
                classRenameBatches++
            } else {
                errors.addAll(batchResult.errors.map { "Class batch: $it" })
            }
        }

        // ═══ Post: XML + ProGuard + Files ═══
        val classMap = unchecked.associate { it.oldName to it.newName }
        if (classMap.isNotEmpty()) {
            val scope = GlobalSearchScope.projectScope(project)
            val classReferenceFiles = buildSet {
                addAll(FilenameIndex.getAllFilesByExt(project, "xml", scope))
                addAll(FilenameIndex.getAllFilesByExt(project, "pro", scope))
                for (name in PROGUARD_FILE_NAMES) {
                    addAll(FilenameIndex.getVirtualFilesByName(project, name, scope))
                }
            }
            for (vf in classReferenceFiles) {
                applyToDoc(vf) { ClassReferenceRewriter.rewrite(it, classMap) }
            }
        }

        // Resource text changes can shift arbitrary Kotlin offsets, so apply them only after all
        // symbol/class pointer-based refactorings are complete.
        val resourceResult = ResourceRefactorExecutor(project).execute(plan.resourceRenames)
        drawablesRenamed = resourceResult.drawablesRenamed
        layoutsRenamed = resourceResult.layoutsRenamed
        filesRenamed += resourceResult.filesRenamed
        referencesUpdated += resourceResult.referencesUpdated
        warnings.addAll(resourceResult.warnings)
        val stringResult = StringResourceRefactorExecutor(project).execute(plan.stringResourceRenames)
        stringsRenamed = stringResult.stringsRenamed
        referencesUpdated += stringResult.referencesUpdated
        warnings.addAll(stringResult.warnings)
        // Diagnostic trace — one line per symbol (renamed / skipped-with-reason / failed).
        try {
            project.basePath?.let { base ->
                java.io.File(base, ".autorefactor-symbols.log").writeText(symbolLog.joinToString("\n"))
            }
        } catch (_: Exception) {}

        return ExecutionResult(
            success = errors.isEmpty(), classesRenamed = classesRenamed,
            symbolsRenamed = symbolsRenamed, referencesUpdated = referencesUpdated,
            filesRenamed = filesRenamed, errors = errors, warnings = warnings,
            drawablesRenamed = drawablesRenamed, layoutsRenamed = layoutsRenamed,
            typeAliasesRenamed = typeAliasesRenamed,
            stringsRenamed = stringsRenamed,
            durationMs = System.currentTimeMillis() - startTime,
            classRenameBatches = classRenameBatches,
        )
    }

    private fun createClassTargets(renames: List<ComponentRename>): List<ClassTarget> =
        ReadAction.compute<List<ClassTarget>, RuntimeException> {
            val pointerManager = SmartPointerManager.getInstance(project)
            val declarationsByFile = renames.map { it.sourceFile }.distinct().associateWith { path ->
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(path)
                val file = virtualFile?.let { PsiManager.getInstance(project).findFile(it) }
                when (file) {
                    is KtFile -> file.collectDescendantsOfType<KtClassOrObject>()
                    is PsiJavaFile -> file.classes.filter { it.containingClass == null }
                    else -> emptyList()
                }
            }
            renames.map { rename ->
                val declaration = declarationsByFile[rename.sourceFile]
                    .orEmpty()
                    .filterIsInstance<PsiNamedElement>()
                    .firstOrNull { hasDeclaration(rename.declarationOffset, rename.oldName, it) }
                ClassTarget(rename, declaration?.let { pointerManager.createSmartPsiElementPointer(it) })
            }
        }

    private fun createSymbolTargets(renames: List<SymbolRename>): List<SymbolTarget> =
        ReadAction.compute<List<SymbolTarget>, RuntimeException> {
            val pointerManager = SmartPointerManager.getInstance(project)
            renames.map { rename ->
                val declaration = findDeclaration(rename)
                SymbolTarget(
                    rename,
                    declaration?.let { pointerManager.createSmartPsiElementPointer(it) },
                )
            }
        }

    private fun createTypeAliasTargets(renames: List<TypeAliasRename>): List<TypeAliasTarget> =
        ReadAction.compute<List<TypeAliasTarget>, RuntimeException> {
            val pointerManager = SmartPointerManager.getInstance(project)
            renames.map { rename ->
                val virtualFile = LocalFileSystem.getInstance().findFileByPath(rename.sourceFile)
                val file = virtualFile?.let { PsiManager.getInstance(project).findFile(it) as? KtFile }
                val declaration = file?.collectDescendantsOfType<KtTypeAlias>()?.firstOrNull {
                    it.textRange.startOffset == rename.declarationOffset && it.name == rename.oldName
                }
                TypeAliasTarget(
                    rename,
                    declaration?.let { pointerManager.createSmartPsiElementPointer(it) },
                )
            }
        }

    private fun findDeclaration(rename: SymbolRename): PsiNamedElement? {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(rename.sourceFile) ?: return null
        return when (val file = PsiManager.getInstance(project).findFile(virtualFile)) {
            is KtFile -> file.collectDescendantsOfType<KtNamedDeclaration>().firstOrNull { declaration ->
                if (!hasDeclaration(rename.declarationOffset, rename.oldName, declaration)) {
                    return@firstOrNull false
                }
                when (rename.kind) {
                    SymbolKind.FUNCTION -> declaration is KtNamedFunction
                    SymbolKind.PROPERTY, SymbolKind.FIELD ->
                        declaration is KtProperty || declaration is KtParameter && declaration.hasValOrVar()
                    else -> false
                }
            }
            is PsiJavaFile -> findJavaDeclaration(file, rename)
            else -> null
        }
    }

    private fun findJavaDeclaration(file: PsiJavaFile, rename: SymbolRename): PsiNamedElement? {
        val classes = file.classes.asSequence()
            .filter { it.qualifiedName == rename.ownerScope }
            .ifEmpty { file.classes.asSequence() }
        return when (rename.kind) {
            SymbolKind.FUNCTION -> classes
                .flatMap { it.methods.asSequence() }
                .firstOrNull { method ->
                    !method.isConstructor && hasDeclaration(rename.declarationOffset, rename.oldName, method)
                }
            SymbolKind.PROPERTY, SymbolKind.FIELD -> classes
                .flatMap { it.fields.asSequence() }
                .firstOrNull { field -> hasDeclaration(rename.declarationOffset, rename.oldName, field) }
            else -> null
        }
    }

    private fun hasDeclaration(offset: Int, name: String, element: PsiNamedElement): Boolean {
        if (element.name != name) return false
        val identifierOffset = (element as? PsiNameIdentifierOwner)?.nameIdentifier?.textRange?.startOffset
        return offset == element.textRange.startOffset || offset == identifierOffset
    }

    private fun renameTypeAlias(declaration: KtTypeAlias, newName: String) {
        runImmediateRename(declaration, newName)
    }

    private fun runImmediateRename(
        declaration: PsiElement,
        newName: String,
    ) {
        val action = {
            val processor = AutoAcceptRenameProcessor(project, declaration, newName)
            processor.respectAllAutomaticRenames(listOf(declaration))
            processor.run()
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) action() else application.invokeAndWait(action)
    }

    // ─────── Universal symbol rename: ReferencesSearch + OverridingMethodsSearch ───────
    private fun renameSymbol(
        rename: SymbolRename,
        element: PsiNamedElement?,
        log: MutableList<String>,
        overrideIndex: MutableMap<String, List<Rep>>,
    ): Boolean {
        val tag = "${rename.oldName}->${rename.newName} [${rename.kind}]"

        // Resolve by OLD name first. Only treat as "already renamed" when the old declaration is
        // truly gone — otherwise a same-named sibling elsewhere (e.g. an already-present
        // `setBindingV2` in another class or an override) would wrongly block a `setBinding` that
        // still needs renaming.
        if (element == null) {
            log.add("SKIP $tag: declaration pointer is no longer valid in ${rename.sourceFile}")
            return false
        }
        val current = element.name ?: ""
        if (current == rename.newName) {
            log.add("SKIP $tag: current name '$current'"); return false
        }

        val count = renameViaReferences(element, rename.newName, overrideIndex)
        log.add((if (count > 0) "OK   " else "NOOP ") + "$tag via ${element.javaClass.simpleName}: $count edit(s)")
        return count > 0
    }

    private fun renameViaReferences(
        element: PsiNamedElement,
        newName: String,
        overrideIndex: MutableMap<String, List<Rep>>,
    ): Int {
        val scope = GlobalSearchScope.projectScope(project)
        val oldName = element.name ?: return 0
        val reps = mutableListOf<Rep>()
        addDecl(element, reps)

        val searchElements = resolveSearchElements(element)

        // References
        for (se in searchElements) {
            for (ref in ReferencesSearch.search(se, scope)) {
                val el = ref.element
                if (el === element || el === (element as? PsiNameIdentifierOwner)?.nameIdentifier ||
                    el === se || el === (se as? PsiNameIdentifierOwner)?.nameIdentifier) continue
                el.containingFile?.virtualFile?.path?.let { reps.add(Rep(it, el.textRange.startOffset, el.textRange.endOffset)) }
            }
        }

        // Overrides (Java + light): run OverridingMethodsSearch on any PsiMethod in the search set
        // (the function's own light method), since `element` itself is now Kotlin PSI, not a PsiMethod.
        for (se in searchElements) {
            if (se is PsiMethod) {
                for (ov in OverridingMethodsSearch.search(se, scope, true)) addDecl(ov, reps)
            }
        }

        // Kotlin override declarations — contribute to `reps` (do NOT edit here). OverridingMethodsSearch
        // may miss some; but editing mid-collection would shift offsets already recorded in reps,
        // causing double-suffix on the override name and skipped super.x() calls. The single apply
        // loop below (dedup + descending offsets) handles everything safely.
        val enclosingFunction = (element as? KtNamedFunction)?.let {
            PsiTreeUtil.getParentOfType(it, KtNamedFunction::class.java, true)
        }
        if (element is KtNamedFunction && enclosingFunction == null) {
            reps.addAll(overrideIndex.remove(oldName).orEmpty())
        }

        var count = 0
        val uniqueReps = reps.distinctBy { "${it.file}:${it.start}:${it.end}" }
        for ((fp, fr) in uniqueReps.groupBy { it.file }) {
            val f = LocalFileSystem.getInstance().findFileByPath(fp) ?: continue
            val p = PsiManager.getInstance(project).findFile(f) ?: continue
            val dm = PsiDocumentManager.getInstance(project)
            val doc = dm.getDocument(p) ?: continue
            WriteCommandAction.runWriteCommandAction(project) {
                for (r in fr.sortedByDescending { it.start }) {
                    if (r.start !in 0..<doc.textLength || r.end > doc.textLength) continue
                    val rt = doc.getText(com.intellij.openapi.util.TextRange(r.start, r.end))
                    val capOld = oldName.replaceFirstChar { it.uppercase() }
                    val capNew = newName.replaceFirstChar { it.uppercase() }
                    when {
                        rt == oldName -> { doc.replaceString(r.start, r.end, newName); count++ }
                        rt == "get$capOld" -> { doc.replaceString(r.start, r.end, "get$capNew"); count++ }
                        rt == "set$capOld" -> { doc.replaceString(r.start, r.end, "set$capNew"); count++ }
                        rt == "is$capOld" -> { doc.replaceString(r.start, r.end, "is$capNew"); count++ }
                        rt.contains("@$oldName") -> {
                            val at = rt.lastIndexOf("@$oldName")
                            if (at >= 0) { doc.replaceString(r.start + at + 1, r.start + at + 1 + oldName.length, newName); count++ }
                        }
                        rt.contains(oldName) -> {
                            val regex = Regex("\\b$oldName\\b")
                            val match = regex.find(rt)
                            if (match != null) {
                                doc.replaceString(r.start + match.range.first, r.start + match.range.last + 1, newName)
                                count++
                            }
                        }
                    }
                }
                dm.commitDocument(doc)
            }
        }
        return count
    }

    /** Scans Kotlin files once and indexes project overrides for every selected function name. */
    private fun buildKotlinOverrideIndex(oldNames: Set<String>): MutableMap<String, List<Rep>> {
        if (oldNames.isEmpty()) return mutableMapOf()
        return ReadAction.compute<MutableMap<String, List<Rep>>, RuntimeException> {
            val result = mutableMapOf<String, MutableList<Rep>>()
            try {
                for (vf in FilenameIndex.getAllFilesByExt(project, "kt", GlobalSearchScope.projectScope(project))) {
                    val ktFile = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: continue
                    for (fn in PsiTreeUtil.findChildrenOfType(ktFile, KtNamedFunction::class.java)) {
                        val name = fn.name ?: continue
                        if (name !in oldNames) continue
                        if (!K2Analysis.overridesProjectDeclarationNamed(fn, name)) continue
                        val identifier = fn.nameIdentifier ?: continue
                        result.getOrPut(name) { mutableListOf() }.add(
                            Rep(vf.path, identifier.textRange.startOffset, identifier.textRange.endOffset),
                        )
                    }
                }
            } catch (_: Exception) {}
            result.mapValuesTo(mutableMapOf()) { (_, reps) ->
                reps.distinctBy { "${it.file}:${it.start}:${it.end}" }
            }
        }
    }

    // ─────── Collect the elements to run ReferencesSearch on ───────
    // `element` is now the real declaration (KtNamedFunction / KtProperty / Java PsiField or method).
    // We add the exact light accessors/methods so Java + light call sites are found too — using
    // LightClassUtil / toLightMethods (precise) rather than findMethodsByName (which collides by
    // name, e.g. `setBinding` of `var binding` vs `fun setBinding(...)`).
    private fun resolveSearchElements(element: PsiNamedElement): List<PsiElement> {
        val list = mutableListOf<PsiElement>(element)
        when (element) {
            is PsiField -> {
                val psiClass = element.containingClass
                if (psiClass != null) {
                    val cap = element.name.replaceFirstChar { it.uppercase() }
                    psiClass.findMethodsByName("get$cap", false).firstOrNull()?.let { list.add(it) }
                    psiClass.findMethodsByName("set$cap", false).firstOrNull()?.let { list.add(it) }
                    psiClass.findMethodsByName("is$cap", false).firstOrNull()?.let { list.add(it) }
                }
            }
            is KtProperty -> {
                // Real function names in the same class. An accessor whose name collides with one
                // (property `binding` -> setter `setBinding`, and there is a genuine `fun setBinding`)
                // must NOT be searched: Kotlin's reference search is name-based, so it would drag the
                // function's declaration/calls/overrides into this property rename and the
                // `set$capOld` rewrite branch would corrupt them.
                val siblingFnNames = element.containingClassOrObject?.declarations
                    ?.filterIsInstance<KtNamedFunction>()?.mapNotNull { it.name }?.toSet() ?: emptySet()
                try {
                    val accessors = LightClassUtil.getLightClassPropertyMethods(element)
                    accessors.getter?.let { if (it.name !in siblingFnNames) list.add(it) }
                    accessors.setter?.let { if (it.name !in siblingFnNames) list.add(it) }
                } catch (_: Throwable) {}
            }
            is KtNamedFunction -> {
                // The function's own light method(s) — for Java/light call sites and override search.
                try {
                    element.toLightMethods().forEach { list.add(it) }
                } catch (_: Throwable) {}
            }
        }
        return list
    }

    private fun addDecl(el: PsiNamedElement, reps: MutableList<Rep>) {
        val d = (el as? PsiNameIdentifierOwner)?.nameIdentifier
        val r = d?.textRange ?: el.textRange
        el.containingFile?.virtualFile?.path?.let { reps.add(Rep(it, r.startOffset, r.endOffset)) }
    }

    // ─────── Find PsiElement by symbol kind ───────
    // For Kotlin, resolve to the REAL declaration (KtNamedFunction / KtProperty) up front — never a
    // synthetic light accessor. A `var binding` generates a light setter `setBinding(value)` that
    // collides by name with a genuine `fun setBinding(...)`; picking the first light method by name
    // would target the property's accessor instead of the function, leaving the real declaration
    // unrenamed while its overrides are renamed (base/override mismatch).
    private fun findElement(pf: PsiFile, parentFqn: String, name: String, kind: SymbolKind): PsiNamedElement? {
        if (pf is KtFile) {
            findInKtFile(pf, parentFqn, name, kind)?.let { return it }
        }
        // Java (or Kotlin with no source match): light-class members.
        val classes = if (pf is PsiClassOwner) pf.classes.toList() else emptyList()
        val cls = classes.find { it.qualifiedName == parentFqn }
            ?: classes.find { parentFqn.endsWith(".${it.name}") }
        if (cls != null) {
            for (f in cls.fields) { if (f.name == name) return f }
            for (m in cls.methods) {
                if (m.name == name && !isAccessorLike(m.name, name)) return m
            }
        }
        return null
    }

    private fun isAccessorLike(methodName: String, targetName: String): Boolean {
        if (methodName == targetName) return false
        val cap = targetName.replaceFirstChar { it.uppercase() }
        return methodName == "get$cap" || methodName == "set$cap" || methodName == "is$cap"
    }

    // Resolve the real Kotlin declaration by kind, scoped to the class whose fqName matches
    // [parentFqn] (falls back to the whole file). FUNCTION -> KtNamedFunction (prefer the
    // non-override base declaration); PROPERTY -> KtProperty. Never returns a synthetic accessor,
    // and never matches a same-named member of a different class in the same file.
    private fun findInKtFile(pf: PsiFile, parentFqn: String, name: String, kind: SymbolKind): PsiNamedElement? {
        val ktFile = pf as? KtFile ?: return null
        val cls = PsiTreeUtil.findChildrenOfType(ktFile, KtClassOrObject::class.java)
            .firstOrNull { it.fqName?.asString() == parentFqn }
        val members: List<KtDeclaration> = cls?.declarations ?: ktFile.declarations
        return when (kind) {
            SymbolKind.FUNCTION -> {
                val fns = members.filterIsInstance<KtNamedFunction>().filter { it.name == name }
                fns.firstOrNull { it.modifierList?.text?.contains("override") != true } ?: fns.firstOrNull()
            }
            else -> members.filterIsInstance<KtProperty>().firstOrNull { it.name == name }
        }
    }

    private fun applyToDoc(vf: VirtualFile, transform: (String) -> String): Int {
        val pf = PsiManager.getInstance(project).findFile(vf) ?: return 0
        val dm = PsiDocumentManager.getInstance(project)
        val doc = dm.getDocument(pf) ?: return 0
        val nu = transform(doc.text)
        if (nu == doc.text) return 0
        WriteCommandAction.runWriteCommandAction(project) { doc.setText(nu); dm.commitDocument(doc) }
        return 1
    }

    private fun renameFile(rename: FileRename): Boolean {
        val vf = LocalFileSystem.getInstance().findFileByPath(rename.oldPath) ?: return false
        if (vf.parent?.findChild(rename.newFileName) != null) return false
        var renamed = false
        WriteCommandAction.runWriteCommandAction(project) {
            if (vf.isValid && vf.parent?.findChild(rename.newFileName) == null) {
                vf.rename(this, rename.newFileName)
                renamed = true
            }
        }
        return renamed
    }

    private companion object {
        val PROGUARD_FILE_NAMES = setOf(
            "proguard-rules.pro",
            "proguard-rules.txt",
            "proguard.cfg",
        )
    }
}
