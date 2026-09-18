package com.org.refactor.plugin.executor

/** Rewrites mapped class names in Android name attributes with one text pass. */
internal object ClassReferenceRewriter {

    private val androidNameAttribute = Regex("""(android:name\s*=\s*)([\"'])(.*?)\2""")

    fun rewrite(text: String, classMap: Map<String, String>): String {
        if (classMap.isEmpty()) return text
        return androidNameAttribute.replace(text) { match ->
            val original = match.groupValues[3]
            val replacement = classMap[original]
                ?: original.removePrefix(".").let { classMap[it] }
                ?: return@replace match.value
            val qualified = original.startsWith(".") && !replacement.startsWith(".")
            val rendered = if (qualified) ".${replacement}" else replacement
            "${match.groupValues[1]}${match.groupValues[2]}$rendered${match.groupValues[2]}"
        }
    }
}
