package com.org.refactor.plugin.executor

/** Updates JNI class references in native source without changing comments or unrelated identifiers. */
internal object CppClassReferenceRewriter {

    private val descriptor = Regex("L([A-Za-z0-9_/$]+);")
    private val nativeMethod = Regex("\\bJava_[A-Za-z0-9_]+")

    fun rewrite(text: String, classMap: Map<String, String>): String {
        if (classMap.isEmpty()) return text
        val slashNames = classMap.mapKeys { (old, _) -> old.replace('.', '/') }
            .mapValues { (_, new) -> new.replace('.', '/') }
        val nativePrefixes = classMap.entries.map { (old, new) ->
            "Java_${jniMangle(old)}_" to "Java_${jniMangle(new)}_"
        }.sortedByDescending { it.first.length }

        fun rewriteCode(code: String): String = nativeMethod.replace(code) { match ->
            val name = match.value
            val prefix = nativePrefixes.firstOrNull { name.startsWith(it.first) }
            if (prefix == null) name else prefix.second + name.removePrefix(prefix.first)
        }

        fun rewriteString(value: String): String {
            classMap[value]?.let { return it }
            slashNames[value]?.let { return it }
            return descriptor.replace(value) { match ->
                val replacement = slashNames[match.groupValues[1]] ?: return@replace match.value
                "L$replacement;"
            }
        }

        val result = StringBuilder(text.length)
        var codeStart = 0
        var index = 0
        while (index < text.length) {
            val next = text.getOrNull(index + 1)
            val kind = when {
                text[index] == '/' && next == '/' -> 1
                text[index] == '/' && next == '*' -> 2
                text[index] == '"' -> 3
                text[index] == '\'' -> 4
                else -> 0
            }
            if (kind == 0) {
                index++
                continue
            }
            result.append(rewriteCode(text.substring(codeStart, index)))
            val start = index
            when (kind) {
                1 -> {
                    index = text.indexOf('\n', index + 2).let { if (it < 0) text.length else it }
                    result.append(text, start, index)
                }
                2 -> {
                    index = text.indexOf("*/", index + 2).let { if (it < 0) text.length else it + 2 }
                    result.append(text, start, index)
                }
                else -> {
                    val quote = text[index++]
                    while (index < text.length) {
                        if (text[index] == '\\') {
                            index = (index + 2).coerceAtMost(text.length)
                        } else if (text[index++] == quote) {
                            break
                        }
                    }
                    if (kind == 3 && index <= text.length && text.getOrNull(index - 1) == quote) {
                        result.append(quote)
                        result.append(rewriteString(text.substring(start + 1, index - 1)))
                        result.append(quote)
                    } else {
                        result.append(text, start, index)
                    }
                }
            }
            codeStart = index
        }
        result.append(rewriteCode(text.substring(codeStart)))
        return result.toString()
    }

    private fun jniMangle(name: String): String = buildString {
        for (char in name) {
            when (char) {
                '.' -> append('_')
                '_' -> append("_1")
                '$' -> append("_00024")
                else -> append(char)
            }
        }
    }
}
