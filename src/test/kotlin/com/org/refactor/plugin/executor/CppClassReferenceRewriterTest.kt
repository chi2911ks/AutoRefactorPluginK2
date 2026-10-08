package com.org.refactor.plugin.executor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CppClassReferenceRewriterTest {

    @Test
    fun `updates JNI class paths descriptors and exported methods`() {
        val original = """
            auto cls = env->FindClass("sample/NativeScreen");
            auto type = "sample.NativeScreen";
            auto signature = "(Lsample/NativeScreen;[Lsample/NativeScreen;)V";
            JNIEXPORT void JNICALL Java_sample_NativeScreen_open(JNIEnv*, jobject) {}
        """.trimIndent()
        val expected = """
            auto cls = env->FindClass("sample/NativeScreenRef");
            auto type = "sample.NativeScreenRef";
            auto signature = "(Lsample/NativeScreenRef;[Lsample/NativeScreenRef;)V";
            JNIEXPORT void JNICALL Java_sample_NativeScreenRef_open(JNIEnv*, jobject) {}
        """.trimIndent()
        val mapping = mapOf("sample.NativeScreen" to "sample.NativeScreenRef")

        assertEquals(expected, CppClassReferenceRewriter.rewrite(original, mapping))
        assertEquals(expected, CppClassReferenceRewriter.rewrite(expected, mapping))
    }

    @Test
    fun `preserves comments character literals and similarly named classes`() {
        val original = """
            // Java_sample_NativeScreen_open and sample/NativeScreen
            /* Lsample/NativeScreen; */
            auto other = env->FindClass("sample/NativeScreenExtra");
            auto otherMethod = Java_sample_NativeScreenExtra_open;
            auto character = 'J';
            auto escaped = "say \"sample/NativeScreen\"";
        """.trimIndent()

        assertEquals(
            original,
            CppClassReferenceRewriter.rewrite(
                original,
                mapOf("sample.NativeScreen" to "sample.NativeScreenRef"),
            ),
        )
    }

    @Test
    fun `mangles underscores in JNI export names`() {
        assertEquals(
            "Java_sample_Native_1ScreenRef_open",
            CppClassReferenceRewriter.rewrite(
                "Java_sample_Native_1Screen_open",
                mapOf("sample.Native_Screen" to "sample.Native_ScreenRef"),
            ),
        )
    }
}
