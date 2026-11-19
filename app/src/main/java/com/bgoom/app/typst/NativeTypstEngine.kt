package com.bgoom.app.typst

/**
 * Embedded Rust engine (`libbgoom_typst.so`):
 * - Offline Typst 0.14.2 -> PDF compiler using Android system CJK fonts + embedded Math/Serif fonts
 * - Sub-millisecond deterministic AST LaTeX <-> Typst transpiler powered by Tylax (mitex-parser + typst-syntax)
 */
object NativeTypstEngine {
    @Volatile
    private var loaded = false

    private fun ensureLoaded() {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("bgoom_typst")
                    loaded = true
                }
            }
        }
    }

    private external fun nativeCompileToPdf(source: String, extraFontDirs: String): ByteArray
    private external fun nativeLoadedFonts(extraFontDirs: String): String
    private external fun nativeLatexToTypst(latex: String, cleanStyle: Boolean): String
    private external fun nativeTypstToLatex(typstSource: String): String

    fun compileToPdf(source: String, extraFontDirs: String = ""): ByteArray {
        ensureLoaded()
        val prepared = ensureCjkFallbackHeader(source)
        return nativeCompileToPdf(prepared, extraFontDirs)
    }

    fun loadedFontFamilies(extraFontDirs: String = ""): List<String> {
        ensureLoaded()
        return nativeLoadedFonts(extraFontDirs)
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Deterministic AST-level LaTeX -> Typst conversion (<1 ms).
     */
    fun latexToTypst(latex: String, cleanStyle: Boolean = true): String {
        if (latex.isBlank()) return ""
        ensureLoaded()
        return nativeLatexToTypst(latex, cleanStyle)
    }

    /**
     * Deterministic AST-level Typst -> LaTeX conversion (<1 ms).
     */
    fun typstToLatex(typstSource: String): String {
        if (typstSource.isBlank()) return ""
        ensureLoaded()
        return nativeTypstToLatex(typstSource)
    }

    /**
     * If user's Typst source doesn't explicitly set a font, prepend a clean Android/macOS CJK + Math fallback stack
     * so Chinese characters never render as tofu offline on Honor/Android devices.
     */
    fun ensureCjkFallbackHeader(source: String): String {
        val hasTextFont = Regex("""#set\s+text\s*\([^)]*font\s*:""").containsMatchIn(source)
        if (hasTextFont) return source
        val header = """
            #set page(paper: "a4", margin: 2cm)
            #set text(
              font: ("Noto Sans CJK SC", "Noto Serif CJK SC", "Honor Sans", "HarmonyOS Sans SC", "Droid Sans Fallback", "PingFang SC", "Libertinus Serif"),
              size: 11pt,
              lang: "zh",
            )
            #set par(leading: 0.72em, justify: true)

        """.trimIndent()
        return header + "\n" + source
    }
}
