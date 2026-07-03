package com.org.refactor.plugin.psi

import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.symbols.KaKotlinPropertySymbol
import org.jetbrains.kotlin.analysis.api.symbols.KaNamedFunctionSymbol
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Centralised K2 Analysis API access.
 *
 * Every semantic question runs inside an `analyze { }` block (the K2 compiler frontend) with a
 * structural-PSI fallback, so the plugin keeps working even if a specific symbol member differs
 * across the 251..261 platform range. This replaces the old reflection approach
 * (`Class.forName("org.jetbrains.kotlin.psi.KtProperty")`): with `<depends>org.jetbrains.kotlin`
 * the PSI classes are on the classpath directly, and semantic questions (backing field? overrides
 * what? does this reference resolve here?) are answered by the frontend instead of by heuristics.
 *
 * Callers MUST already hold a read action — Analysis API calls are forbidden from write actions
 * and from the bare EDT.
 */
object K2Analysis {

    /**
     * A property is renameable storage only if it has a real backing field. Computed properties
     * (`val x get() = ...`, expression-body vals) must be skipped so we never rewrite something
     * that is really a reference to another symbol.
     */
    fun hasBackingField(prop: KtProperty): Boolean {
        if (prop.hasDelegate()) return true
        if (prop.hasInitializer()) return true
        try {
            return analyze(prop) {
                (prop.symbol as? KaKotlinPropertySymbol)?.hasBackingField ?: structuralBackingField(prop)
            }
        } catch (_: Throwable) {
            return structuralBackingField(prop)
        }
    }

    private fun structuralBackingField(prop: KtProperty): Boolean {
        val getter = prop.getter
        if (getter != null && getter.hasBody()) return false
        return true
    }

    /**
     * True if [fn] semantically overrides a callable named [targetName]. Uses the K2 override
     * graph (`allOverriddenSymbols`, transitive) — accurate where `OverridingMethodsSearch` and
     * the old `text.startsWith("override ")` heuristic were not.
     */
    fun overridesName(fn: KtNamedFunction, targetName: String): Boolean {
        try {
            return analyze(fn) {
                val sym = fn.symbol as? KaNamedFunctionSymbol ?: return@analyze false
                sym.allOverriddenSymbols.any { (it as? KaNamedFunctionSymbol)?.name?.asString() == targetName }
            }
        } catch (_: Throwable) {
            return fn.name == targetName && fn.modifierList?.text?.contains("override") == true
        }
    }

    /**
     * True only if [fn] overrides a callable named [targetName] that is **declared inside the
     * project** (its PSI is writable source). Overrides of external SDK/library methods — e.g.
     * `override fun handleOnBackPressed()` inside an anonymous `object : OnBackPressedCallback`, or
     * any framework callback in an anonymous object — return false, so the renamer never touches a
     * name whose override contract is owned by a library we don't control.
     */
    fun overridesProjectDeclarationNamed(fn: KtNamedFunction, targetName: String): Boolean {
        try {
            return analyze(fn) {
                val sym = fn.symbol as? KaNamedFunctionSymbol ?: return@analyze false
                sym.allOverriddenSymbols.any { ov ->
                    (ov as? KaNamedFunctionSymbol)?.name?.asString() == targetName && ov.psi?.isWritable == true
                }
            }
        } catch (_: Throwable) {
            // Conservative fallback: accept a same-named override only if it is NOT inside an
            // anonymous object (anonymous-object overrides are almost always external SDK contracts).
            if (fn.name != targetName || fn.modifierList?.text?.contains("override") != true) return false
            return com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                fn, org.jetbrains.kotlin.psi.KtObjectLiteralExpression::class.java
            ) == null
        }
    }

    /**
     * Which of [siblings] (name -> property, all in the same class) does [prop] reference in its own
     * initializer / delegate / getter / setter? Used by the declaration shuffler to keep dependent
     * properties grouped so reordering never breaks initialization order.
     *
     * Primary path resolves each name reference inside [prop] via K2 and matches the resolved PSI
     * against the sibling set (accurate — ignores unrelated names, types, locals). Falls back to a
     * word-boundary textual scan if analysis is unavailable.
     */
    fun siblingDependencies(prop: KtProperty, siblings: Map<String, KtProperty>): Set<KtProperty> {
        if (siblings.isEmpty()) return emptySet()
        val bySibling = siblings.values.toHashSet()
        val refs = prop.collectDescendantsOfType<KtNameReferenceExpression>()
        val found = LinkedHashSet<KtProperty>()
        try {
            analyze(prop) {
                for (ref in refs) {
                    val target = ref.mainReference.resolveToSymbols().firstOrNull()?.psi as? KtProperty ?: continue
                    if (target !== prop && target in bySibling) found.add(target)
                }
            }
            return found
        } catch (_: Throwable) {
            // Textual fallback: any sibling name appearing as a whole word inside prop's text.
            val text = prop.text
            for ((name, sib) in siblings) {
                if (sib === prop) continue
                if (Regex("\\b" + Regex.escape(name) + "\\b").containsMatchIn(text)) found.add(sib)
            }
            return found
        }
    }
}
