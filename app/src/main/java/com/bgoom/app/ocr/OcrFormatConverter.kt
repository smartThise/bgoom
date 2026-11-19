package com.bgoom.app.ocr

import com.bgoom.app.data.OcrOutputFormat
import kotlin.math.abs

data class OcrLineBox(
    val text: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val height: Float get() = (bottom - top).coerceAtLeast(1f)
    val centerY: Float get() = (top + bottom) * 0.5f
}

/**
 * Pure deterministic layout-to-markup assembler (Markdown / LaTeX / Typst).
 * Turns detected text lines (and optional math formulas) into clean structured source.
 */
object OcrFormatConverter {

    fun assembleLines(lines: List<OcrLineBox>, format: OcrOutputFormat): String {
        if (lines.isEmpty()) return ""
        val sorted = sortReadingOrder(lines)
        val medianH = sorted.map { it.height }.sorted().let { it[it.size / 2] }.coerceAtLeast(10f)

        val blocks = groupIntoParagraphs(sorted, medianH)

        return when (format) {
            OcrOutputFormat.MARKDOWN -> blocks.joinToString("\n\n") { block ->
                when (block.kind) {
                    BlockKind.HEADING -> "## ${block.text}"
                    BlockKind.LIST_ITEM -> "- ${block.text.removePrefix("•").removePrefix("-").removePrefix("·").trim()}"
                    BlockKind.PARAGRAPH -> block.text
                }
            }

            OcrOutputFormat.TYPST -> blocks.joinToString("\n\n") { block ->
                val escaped = escapeTypstBody(block.text)
                when (block.kind) {
                    BlockKind.HEADING -> "= $escaped"
                    BlockKind.LIST_ITEM -> "- ${escaped.removePrefix("•").removePrefix("-").removePrefix("·").trim()}"
                    BlockKind.PARAGRAPH -> escaped
                }
            }

            OcrOutputFormat.LATEX -> buildString {
                append("\\documentclass{article}\n\\usepackage{ctex}\n\\usepackage{amsmath,amssymb}\n\\begin{document}\n\n")
                append(
                    blocks.joinToString("\n\n") { block ->
                        val escaped = escapeLatexBody(block.text)
                        when (block.kind) {
                            BlockKind.HEADING -> "\\section*{$escaped}"
                            BlockKind.LIST_ITEM -> "\\noindent $\\bullet$ ${escaped.removePrefix("•").removePrefix("-").removePrefix("·").trim()}"
                            BlockKind.PARAGRAPH -> escaped
                        }
                    }
                )
                append("\n\n\\end{document}\n")
            }
        }
    }

    fun stripCodeFence(raw: String): String {
        val trimmed = raw.trim()
        val fenceRegex = Regex("^```[a-zA-Z0-9_-]*\\s*\\n([\\s\\S]*?)\\n```$")
        return fenceRegex.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed
    }

    fun sortReadingOrder(lines: List<OcrLineBox>): List<OcrLineBox> {
        if (lines.size <= 1) return lines
        val byTop = lines.sortedBy { it.top }
        val rows = mutableListOf<MutableList<OcrLineBox>>()
        for (line in byTop) {
            val row = rows.lastOrNull()
            if (row != null && abs(line.centerY - row.first().centerY) < minOf(line.height, row.first().height) * 0.55f) {
                row.add(line)
            } else {
                rows.add(mutableListOf(line))
            }
        }
        return rows.flatMap { r -> r.sortedBy { it.left } }
    }

    private enum class BlockKind { HEADING, LIST_ITEM, PARAGRAPH }
    private data class TextBlock(val kind: BlockKind, val text: String)

    private fun groupIntoParagraphs(sorted: List<OcrLineBox>, medianH: Float): List<TextBlock> {
        val result = mutableListOf<TextBlock>()
        var currentLines = mutableListOf<OcrLineBox>()

        fun flushParagraph() {
            if (currentLines.isEmpty()) return
            val joined = joinSmart(currentLines.map { it.text.trim() })
            result.add(TextBlock(BlockKind.PARAGRAPH, joined))
            currentLines = mutableListOf()
        }

        for (line in sorted) {
            val text = line.text.trim()
            if (text.isEmpty()) continue

            val isHeading = line.height > medianH * 1.32f && text.length <= 42
            val isBullet = text.startsWith("•") || text.startsWith("·") ||
                Regex("^(\\d+[.、)]|[-*])\\s+").containsMatchIn(text)

            if (isHeading) {
                flushParagraph()
                result.add(TextBlock(BlockKind.HEADING, text))
                continue
            }
            if (isBullet) {
                flushParagraph()
                result.add(TextBlock(BlockKind.LIST_ITEM, text))
                continue
            }

            val prev = currentLines.lastOrNull()
            if (prev != null) {
                val verticalGap = line.top - prev.bottom
                if (verticalGap > medianH * 0.95f) {
                    flushParagraph()
                }
            }
            currentLines.add(line)
        }
        flushParagraph()
        return result
    }

    private fun joinSmart(parts: List<String>): String {
        if (parts.isEmpty()) return ""
        val sb = StringBuilder(parts.first())
        for (i in 1 until parts.size) {
            val prev = sb.lastOrNull()
            val next = parts[i].firstOrNull()
            val needsSpace = prev != null && next != null &&
                prev.code < 128 && next.code < 128 &&
                !prev.isWhitespace() && !next.isWhitespace()
            if (needsSpace) sb.append(' ')
            sb.append(parts[i])
        }
        return sb.toString()
    }

    private fun escapeTypstBody(s: String): String =
        s.replace("\\", "\\\\")
            .replace("#", "\\#")
            .replace("$", "\\$")
            .replace("@", "\\@")
            .replace("<", "\\<")
            .replace(">", "\\>")

    private fun escapeLatexBody(s: String): String =
        s.replace("\\", "\\textbackslash{}")
            .replace("%", "\\%")
            .replace("&", "\\&")
            .replace("#", "\\#")
            .replace("_", "\\_")
            .replace("$", "\\$")
}
