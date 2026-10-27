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
                        stringLiterals.findAll(body).filterNot { literal ->
                            val value = literal.groupValues[1]
                            value in exactQuoteAllowList || copyRanges.any { literal.range.first in it } ||
                                !value.replace(Regex("\\\\u[0-9a-fA-F]{4}"), "").any { it.isLetterOrDigit() } ||
                                Regex("^\\$\\{?[A-Za-z0-9_.]+}?$").matches(value)
                        }.map { literal ->
                            val offset = openParen + 1 + literal.range.first
                            "${file.relativeTo(main)}:${source.take(offset).count { it == '\n' } + 1}: ${literal.groupValues[1]}"
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
    private val stringLiterals = Regex(""""((?:\\.|[^"\\])*)"""")

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
