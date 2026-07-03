package com.org.refactor.plugin.executor

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.search.searches.OverridingMethodsSearch
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import com.org.refactor.plugin.model.*
import com.org.refactor.plugin.psi.K2Analysis
import org.jetbrains.kotlin.asJava.LightClassUtil
import org.jetbrains.kotlin.asJava.toLightMethods
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.containingClassOrObject

class RefactorExecutor(private val project: Project) {

    private data class Rep(val file: String, val start: Int, val end: Int)

    data class ExecutionResult(
        val success: Boolean, val classesRenamed: Int, val symbolsRenamed: Int,
        val referencesUpdated: Int, val filesRenamed: Int,
        val errors: List<String>, val warnings: List<String>, val durationMs: Long,
    )

    fun execute(plan: RefactorPlan): ExecutionResult {
        val startTime = System.currentTimeMillis()
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var classesRenamed = 0; var symbolsRenamed = 0; var filesRenamed = 0
        val symbolLog = mutableListOf<String>()   // per-symbol diagnostic trace

        val unchecked = plan.componentRenames.filter { it.checked && !it.oldName.endsWith(plan.suffix) }
        val byPkg = unchecked.groupBy { it.fqn.substringBeforeLast('.') }
        val sortedPkgs = byPkg.keys.sortedWith(compareBy({ tier(it) }, { it }))
        val allSymbols = plan.symbolRenames.filter { it.checked && !it.oldName.endsWith(plan.suffix) }

        for (pkg in sortedPkgs) {
            val pkgComps = byPkg[pkg] ?: continue
            val fqns = pkgComps.map { it.fqn }.toSet()
            val pkgSymbols = allSymbols.filter { it.fqn.substringBeforeLast('.') in fqns }

            // ═══ All symbols via ReferencesSearch (NO RenameProcessor — avoids getter confusion) ═══
            for (sym in pkgSymbols) {
                try {
                    val ok = ReadAction.compute<Boolean, RuntimeException> { renameSymbol(sym, symbolLog) }
                    if (ok) symbolsRenamed++
                } catch (e: Exception) { errors.add("${sym.oldName}: ${e.message}"); symbolLog.add("FAIL ${sym.oldName}->${sym.newName}: ${e.message}") }
            }

            // ═══ Classes via RenameProcessor (only engine for imports, this@, XML refs) ═══
            for (comp in pkgComps) {
                try {
                    ReadAction.compute<Unit, RuntimeException> { renameClass(comp) }
                    classesRenamed++
                } catch (e: Exception) { errors.add("${comp.oldName}: ${e.message}") }
            }
        }

        // ═══ Post: XML + ProGuard + Files ═══
        val classMap = unchecked.associate { it.oldName to it.newName }
        for (ext in listOf("xml", "pro")) {
            for (vf in FilenameIndex.getAllFilesByExt(project, ext, GlobalSearchScope.projectScope(project))) {
                applyToDoc(vf) { replaceClassNames(it, classMap) }
            }
        }
        project.baseDir?.let { baseDir ->
            VfsUtilCore.visitChildrenRecursively(baseDir, object : com.intellij.openapi.vfs.VirtualFileVisitor<Void>() {
                override fun visitFile(f: VirtualFile): Boolean {
                    if (!f.isDirectory && f.name in setOf("proguard-rules.pro", "proguard-rules.txt", "proguard.cfg"))
                        applyToDoc(f) { replaceClassNames(it, classMap) }
                    return true
                }
            })
        }

        for (r in plan.fileRenames) {
            try {
                val vf = LocalFileSystem.getInstance().findFileByPath(r.oldPath) ?: continue
                if (vf.parent?.findChild(r.newFileName) == null) { vf.rename(this, r.newFileName); filesRenamed++ }
            } catch (_: Exception) {}
        }
        VirtualFileManager.getInstance().syncRefresh()

        // Diagnostic trace — one line per symbol (renamed / skipped-with-reason / failed).
        try {
            project.basePath?.let { base ->
                java.io.File(base, ".autorefactor-symbols.log").writeText(symbolLog.joinToString("\n"))
            }
        } catch (_: Exception) {}

        return ExecutionResult(
            success = errors.isEmpty(), classesRenamed = classesRenamed,
            symbolsRenamed = symbolsRenamed, referencesUpdated = 0,
            filesRenamed = filesRenamed, errors = errors, warnings = warnings,
            durationMs = System.currentTimeMillis() - startTime,
        )
    }

    private fun tier(pkg: String) = when {
        pkg.contains(".base") || pkg.contains(".core") || pkg.contains(".common") -> 0
        else -> 1
    }

    private fun renameClass(rename: ComponentRename) {
        val psiClass = JavaPsiFacade.getInstance(project)
            .findClass(rename.fqn, GlobalSearchScope.projectScope(project))
            ?: throw IllegalStateException("Not found: ${rename.fqn}")
        ApplicationManager.getApplication().invokeAndWait {
            RenameProcessor(project, psiClass, rename.newName, false, false).run()
        }
    }

    // ─────── Universal symbol rename: ReferencesSearch + OverridingMethodsSearch ───────
    private fun renameSymbol(rename: SymbolRename, log: MutableList<String>): Boolean {
        val tag = "${rename.oldName}->${rename.newName} [${rename.kind}]"
        val vf = LocalFileSystem.getInstance().findFileByPath(rename.sourceFile)
            ?: run { log.add("SKIP $tag: file not found ${rename.sourceFile}"); return false }
        val pf = PsiManager.getInstance(project).findFile(vf)
            ?: run { log.add("SKIP $tag: psi file null"); return false }
        val name = rename.fqn.substringAfterLast('.')
        val parentFqn = rename.fqn.substringBeforeLast('.')

        // Resolve by OLD name first. Only treat as "already renamed" when the old declaration is
        // truly gone — otherwise a same-named sibling elsewhere (e.g. an already-present
        // `setBindingV2` in another class or an override) would wrongly block a `setBinding` that
        // still needs renaming.
        val element = findElement(pf, parentFqn, name, rename.kind)
        if (element == null) {
            val already = findElement(pf, parentFqn, rename.newName, rename.kind) != null
            log.add("SKIP $tag: " + if (already) "already renamed" else "decl not found in ${pf.name} (isKt=${pf is KtFile})")
            return false
        }
        val current = (element as PsiNamedElement).name ?: ""
        if (current == rename.newName || current.endsWith(rename.newName)) {
            log.add("SKIP $tag: current name '$current'"); return false
        }

        val count = renameViaReferences(element, rename.newName)
        log.add((if (count > 0) "OK   " else "NOOP ") + "$tag via ${element.javaClass.simpleName}: $count edit(s)")
        return count > 0
    }

    private fun renameViaReferences(element: PsiNamedElement, newName: String): Int {
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
        collectKotlinOverrideReps(oldName, reps)

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

    // Record Kotlin `override fun oldName` name-identifiers into [reps] (no editing). Detection uses
    // the K2 override graph (K2Analysis.overridesName) with a structural fallback. Routing through
    // reps — rather than editing here — keeps every recorded offset in the file valid and lets the
    // dedup in the apply loop collapse this with OverridingMethodsSearch's own decl, so the name is
    // renamed exactly once.
    private fun collectKotlinOverrideReps(oldName: String, reps: MutableList<Rep>) {
        try {
            for (vf in FilenameIndex.getAllFilesByExt(project, "kt", GlobalSearchScope.projectScope(project))) {
                val ktFile = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: continue
                val fns = PsiTreeUtil.findChildrenOfType(ktFile, KtNamedFunction::class.java)
                for (fn in fns) {
                    if (fn.name != oldName) continue
                    // Only rename overrides of a PROJECT-declared base. Skip overrides of external
                    // SDK/library methods (e.g. handleOnBackPressed inside an anonymous
                    // object : OnBackPressedCallback) — renaming them breaks the framework contract.
                    if (!K2Analysis.overridesProjectDeclarationNamed(fn, oldName)) continue
                    val nid = fn.nameIdentifier ?: continue
                    val start = nid.textRange.startOffset
                    // Skip if OverridingMethodsSearch already recorded this same declaration
                    // (guard against the KtLightIdentifier vs source-identifier offset mismatch).
                    if (reps.any { it.file == vf.path && it.start == start }) continue
                    reps.add(Rep(vf.path, start, nid.textRange.endOffset))
                }
            }
        } catch (_: Exception) {}
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

    private fun replaceClassNames(t: String, map: Map<String, String>): String {
        var x = t
        for ((o, n) in map) { x = x.replace("android:name=\"$o\"", "android:name=\"$n\"") }
        return x
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
}
