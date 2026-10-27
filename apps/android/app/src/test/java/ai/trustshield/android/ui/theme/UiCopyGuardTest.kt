package ai.trustshield.android.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiCopyGuardTest {
    @Test
    fun composeTextDoesNotReceiveNewRawUiCopy() {
        val main = findMainSourceRoot() ?: error("Cannot locate Android main sources")
        val offenders = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val source = stripComments(file.readText())
                composeTextBodies.findAll(source).flatMap { call ->
                    val openParen = call.range.last
                    val end = closingParen(source, openParen)
                    if (end < 0) emptySequence()
                    else {
                        val body = source.substring(openParen + 1, end)
                        val copyRanges = copyCalls.findAll(body).mapNotNull { copy ->
                            val copyEnd = closingParen(body, copy.range.last)
                            if (copyEnd < 0) null else copy.range.first..copyEnd
                        }.toList()
                        visibleStringLiterals(body).asSequence().filterNot { (offset, value) ->
                            value in exactQuoteAllowList || value == "{id}" ||
                                copyRanges.any { offset in it } ||
                                !value.replace(Regex("\\\\u[0-9a-fA-F]{4}"), "").any { it.isLetterOrDigit() } ||
                                Regex("^\\$\\{?[A-Za-z0-9_.]+}?$").matches(value)
                        }.map { (offset, value) ->
                            val absoluteOffset = openParen + 1 + offset
                            "${file.relativeTo(main)}:${source.take(absoluteOffset).count { it == '\n' } + 1}: $value"
                        }
                    }
                }
            }
            .toList()

        assertTrue("Route visible copy through ToneCopy/SettingsCopy; remaining literals:\n${offenders.joinToString("\n")}", offenders.isEmpty())
    }

    private fun findMainSourceRoot(): File? {
        var dir = File("").absoluteFile
        repeat(6) {
            val candidate = File(dir, "src/main/java/ai/trustshield/android/ui")
            if (candidate.isDirectory) return candidate
            dir = dir.parentFile ?: return null
        }
        return null
    }

    private val exactQuoteAllowList = setOf(
        "Case clear karne ke liye 50,000 bhejo",
        "kuch saaf nahi suna",
    )

    private val composeTextBodies = Regex("""\bText\s*\(""")
    private val copyCalls = Regex("""\b(?:ToneCopy|SettingText|UiCopy)\s*\(""")

    /** Finds visible string content while skipping Kotlin expressions inside `${ ... }`. */
    private fun visibleStringLiterals(source: String): List<Pair<Int, String>> {
        val values = mutableListOf<Pair<Int, String>>()
        var index = 0
        while (index < source.length) {
            if (source[index] != '"') {
                index++
                continue
            }
            val start = index++
            val literal = StringBuilder()
            while (index < source.length && source[index] != '"') {
                when {
                    source[index] == '\\' -> {
                        if (index + 5 < source.length && source[index + 1] == 'u') {
                            val code = source.substring(index + 2, index + 6).toIntOrNull(16)
                            if (code != null) literal.append(code.toChar())
                            index += 6
                        } else {
                            if (index + 1 < source.length) literal.append(source[index + 1])
                            index += 2
                        }
                    }
                    source[index] == '$' && index + 1 < source.length && source[index + 1] == '{' -> {
                        index = skipTemplateExpression(source, index + 2)
                    }
                    source[index] == '$' && index + 1 < source.length && (source[index + 1].isLetterOrDigit() || source[index + 1] == '_') -> {
                        index += 2
                        while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_' || source[index] == '.')) index++
                    }
                    else -> literal.append(source[index++])
                }
            }
            values += start to literal.toString()
            if (index < source.length) index++
        }
        return values
    }

    private fun skipTemplateExpression(source: String, start: Int): Int {
        var depth = 1
        var index = start
        var inString = false
        var escaped = false
        while (index < source.length && depth > 0) {
            val char = source[index]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> depth--
                }
            }
            index++
        }
        return index
    }

    private fun closingParen(source: String, openParen: Int): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in openParen until source.length) {
            val char = source[index]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
            } else {
                when (char) {
                    '"' -> inString = true
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) return index
                    }
                }
            }
        }
        return -1
    }

    private fun stripComments(source: String): String = source
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)//.*$"), "")
}
