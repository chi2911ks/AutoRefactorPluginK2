package com.org.refactor.plugin.executor

/** Rewrites mapped class names in Android name attributes with one text pass. */
internal object ClassReferenceRewriter {

    private val androidNameAttribute = Regex("""(android:name\s*=\s*)([\"'])(.*?)\2""")

    fun rewrite(text: String, classMap: Map<String, String>): String {
        if (classMap.isEmpty()) return text
        return androidNameAttribute.replace(text) { match ->
            val replacement = classMap[match.groupValues[3]] ?: return@replace match.value
            "${match.groupValues[1]}${match.groupValues[2]}$replacement${match.groupValues[2]}"
        }
    }
}
