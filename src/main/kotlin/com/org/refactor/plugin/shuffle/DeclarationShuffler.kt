package com.org.refactor.plugin.shuffle

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.org.refactor.plugin.psi.K2Analysis
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtObjectDeclaration
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSecondaryConstructor

/**
 * Randomly reorders declarations inside Kotlin class bodies, while never breaking compilation:
 *
 *  - Only plain `KtProperty` / `KtNamedFunction` members move. `companion object`, named objects,
 *    nested classes, `init {}` blocks, secondary constructors and enum entries are ANCHORS: they
 *    stay at their exact position AND act as barriers (members never cross them), so a property can
 *    never be moved past an `init {}` that uses it.
 *  - Shuffling is per contiguous same-kind run: properties shuffle among property slots, functions
 *    among function slots (the two zones and the property/function interleaving are preserved).
 *  - Within a property run, properties that reference each other are grouped into an atomic block
 *    (via [K2Analysis.siblingDependencies]) that moves together, keeping source order internally —
 *    e.g. `private var _binding` + `val binding get() = _binding!!` stay adjacent and in order.
 *
 * Known limitation: an indirect eager dependency through a member function call
 * (`val x = helper()` where `helper()` reads another property) is not detected. Rare in Android UI
 * classes; verify compilation after shuffling.
 */
class DeclarationShuffler(private val project: Project) {

    data class Result(val filesChanged: Int, val filesScanned: Int)

    private data class Edit(val start: Int, val end: Int, val newText: String)

    private enum class Kind { PROP, FUNC, ANCHOR }

    fun shuffle(
        filePaths: Collection<String>,
        shuffleFunctions: Boolean = true,
        shuffleVariables: Boolean = true,
    ): Result {
        val ktPaths = filePaths.distinct().filter { it.endsWith(".kt") }
        var changed = 0
        for (path in ktPaths) {
            try {
                if (shuffleFile(path, shuffleFunctions, shuffleVariables)) changed++
            } catch (_: Exception) {}
        }
        return Result(changed, ktPaths.size)
    }

    private fun shuffleFile(path: String, shuffleFunctions: Boolean, shuffleVariables: Boolean): Boolean {
        val edits = ReadAction.compute<List<Edit>, RuntimeException> {
            planEdits(path, shuffleFunctions, shuffleVariables)
        }
        if (edits.isEmpty()) return false
        val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return false
        val pf = PsiManager.getInstance(project).findFile(vf) ?: return false
        val dm = PsiDocumentManager.getInstance(project)
        val doc = dm.getDocument(pf) ?: return false
        WriteCommandAction.runWriteCommandAction(project) {
            // Apply bottom-up so earlier offsets stay valid.
            for (e in edits.sortedByDescending { it.start }) {
                if (e.start < 0 || e.end > doc.textLength || e.start >= e.end) continue
                doc.replaceString(e.start, e.end, e.newText)
            }
            dm.commitDocument(doc)
        }
        return true
    }

    private fun planEdits(path: String, shuffleFunctions: Boolean, shuffleVariables: Boolean): List<Edit> {
        val vf = LocalFileSystem.getInstance().findFileByPath(path) ?: return emptyList()
        val ktFile = PsiManager.getInstance(project).findFile(vf) as? KtFile ?: return emptyList()
        val fileText = ktFile.text
        val edits = mutableListOf<Edit>()
        planDeclarations(ktFile.declarations, fileText, edits, shuffleFunctions, shuffleVariables)
        for (klass in ktFile.declarations.filterIsInstance<KtClassOrObject>()) {
            planClass(klass, fileText, edits, shuffleFunctions, shuffleVariables)
        }
        return edits
    }

    private fun planClass(
        klass: KtClassOrObject,
        fileText: String,
        edits: MutableList<Edit>,
        shuffleFunctions: Boolean,
        shuffleVariables: Boolean,
    ) {
        val members = klass.body?.declarations ?: return
        planDeclarations(members, fileText, edits, shuffleFunctions, shuffleVariables)
        for (nested in members.filterIsInstance<KtClassOrObject>()) {
            planClass(nested, fileText, edits, shuffleFunctions, shuffleVariables)
        }
    }

    private fun planDeclarations(
        members: List<KtDeclaration>,
        fileText: String,
        edits: MutableList<Edit>,
        shuffleFunctions: Boolean,
        shuffleVariables: Boolean,
    ) {
        if (members.size < 2) return
        val n = members.size
        var i = 0
        while (i < n) {
            val kind = kindOf(members[i], shuffleFunctions, shuffleVariables)
            if (kind == Kind.ANCHOR) { i++; continue }
            var j = i
            while (j < n && kindOf(members[j], shuffleFunctions, shuffleVariables) == kind) j++
            if (j - i >= 2) planRun(members.subList(i, j).toList(), kind, fileText)?.let { edits.add(it) }
            i = j
        }
    }

    private fun planRun(run: List<KtDeclaration>, kind: Kind, fileText: String): Edit? {
        val perm: List<Int> = when (kind) {
            Kind.PROP -> {
                val blocks = buildPropBlocks(run).toMutableList()
                if (blocks.size < 2) return null            // one atomic block → nothing to reorder
                blocks.shuffle()
                blocks.flatten()
            }
            Kind.FUNC -> run.indices.shuffled()
            Kind.ANCHOR -> return null
        }
        if (perm.withIndex().all { (slot, orig) -> slot == orig }) return null   // no-op shuffle
        return buildRunEdit(run, perm, fileText)
    }

    /** Connected components of properties linked by intra-run references; each block = indices in source order. */
    private fun buildPropBlocks(run: List<KtDeclaration>): List<List<Int>> {
        val props = run.map { it as KtProperty }
        val byName = HashMap<String, KtProperty>()
        props.forEach { p -> p.name?.let { byName[it] = p } }
        val indexOf = HashMap<KtProperty, Int>()
        props.forEachIndexed { idx, p -> indexOf[p] = idx }

        val parent = IntArray(props.size) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != c) { val nx = parent[c]; parent[c] = r; c = nx }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)   // lower index as root → stable order
        }

        for (idx in props.indices) {
            for (dep in K2Analysis.siblingDependencies(props[idx], byName)) {
                indexOf[dep]?.let { di -> if (di != idx) union(idx, di) }
            }
        }

        val groups = LinkedHashMap<Int, MutableList<Int>>()
        for (idx in props.indices) groups.getOrPut(find(idx)) { mutableListOf() }.add(idx)
        // Block = indices in ascending (source) order; blocks ordered by their first index.
        return groups.values.map { it.sorted() }.sortedBy { it.first() }
    }

    /** Rebuild the run's text with declarations in [perm] order, keeping the separators positional. */
    private fun buildRunEdit(run: List<KtDeclaration>, perm: List<Int>, fileText: String): Edit {
        val start = run.first().textRange.startOffset
        val end = run.last().textRange.endOffset
        val sb = StringBuilder()
        for (slot in run.indices) {
            val d = run[perm[slot]]
            sb.append(fileText, d.textRange.startOffset, d.textRange.endOffset)
            if (slot < run.size - 1) {
                // Separator (whitespace/comments) that originally followed this slot stays here.
                sb.append(fileText, run[slot].textRange.endOffset, run[slot + 1].textRange.startOffset)
            }
        }
        return Edit(start, end, sb.toString())
    }

    private fun kindOf(d: KtDeclaration, shuffleFunctions: Boolean, shuffleVariables: Boolean): Kind = when {
        isAnchor(d) -> Kind.ANCHOR
        d is KtProperty && shuffleVariables -> Kind.PROP
        d is KtNamedFunction && shuffleFunctions -> Kind.FUNC
        else -> Kind.ANCHOR
    }

    // companion/named object, nested class or enum entry (KtClass), init block, secondary constructor.
    private fun isAnchor(d: KtDeclaration): Boolean =
        d is KtObjectDeclaration || d is KtClass || d is KtAnonymousInitializer || d is KtSecondaryConstructor
}
