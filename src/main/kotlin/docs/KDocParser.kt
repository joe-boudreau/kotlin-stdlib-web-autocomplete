package docs

import java.io.File
import java.util.jar.JarFile

data class KDocEntry(
    val memberName: String,
    val receiverType: String?,
    val paramCount: Int,
    val paramNames: List<String>,
    val summary: String,
    val description: String,
    val paramDocs: Map<String, String>,
    val returnDoc: String,
    val since: String,
)

data class KDocKey(
    val memberName: String,
    val receiverType: String?,
    val paramCount: Int,
)

class KDocParser {

    fun parseSourcesJar(jarPath: String): Map<KDocKey, KDocEntry> {
        val jar = JarFile(File(jarPath))
        val result = mutableMapOf<KDocKey, KDocEntry>()

        for (entry in jar.entries().asSequence()) {
            if (!entry.name.endsWith(".kt")) continue
            val source = jar.getInputStream(entry).bufferedReader().readText()
            val entries = parseSource(source)
            for (e in entries) {
                val key = KDocKey(e.memberName, e.receiverType, e.paramCount)
                result.putIfAbsent(key, e)
            }
        }

        jar.close()
        println("Parsed KDoc from ${result.size} declarations")
        return result
    }

    fun parseSource(source: String): List<KDocEntry> {
        val results = mutableListOf<KDocEntry>()
        val lines = source.lines()
        var i = 0

        // Track enclosing type for members inside class/interface bodies
        val typeStack = mutableListOf<String>() // stack of enclosing type names
        var braceDepth = 0
        val typeAtDepth = mutableMapOf<Int, String>() // braceDepth → type name when opened

        while (i < lines.size) {
            val trimmed = lines[i].trimStart()

            // Track brace depth for class/interface scope (skip braces in strings/comments)
            if (!trimmed.startsWith("/**") && !trimmed.startsWith("*") && !trimmed.startsWith("//")) {
                countBraces(lines[i]) { delta ->
                    if (delta > 0) {
                        braceDepth++
                    } else {
                        if (typeAtDepth.containsKey(braceDepth)) {
                            typeStack.removeLastOrNull()
                            typeAtDepth.remove(braceDepth)
                        }
                        braceDepth--
                    }
                }
            }

            // Detect class/interface declarations to track enclosing type
            val classMatch = CLASS_PATTERN.find(trimmed)
            if (classMatch != null && !trimmed.startsWith("/**")) {
                val typeName = classMatch.groupValues[1]
                typeStack.add(typeName)
                typeAtDepth[braceDepth] = typeName
                i++
                continue
            }

            if (trimmed.startsWith("/**")) {
                val kdocLines = mutableListOf<String>()

                if (trimmed.contains("*/") && trimmed.indexOf("*/") > trimmed.indexOf("/**") + 3) {
                    kdocLines.add(trimmed)
                    i++
                } else {
                    while (i < lines.size) {
                        kdocLines.add(lines[i])
                        if (lines[i].contains("*/")) {
                            i++
                            break
                        }
                        i++
                    }
                }

                // Skip annotations between KDoc and declaration
                while (i < lines.size) {
                    val nextTrimmed = lines[i].trimStart()
                    if (nextTrimmed.startsWith("@") ||
                        nextTrimmed.startsWith("//") ||
                        nextTrimmed.isBlank()) {
                        i++
                    } else {
                        break
                    }
                }

                if (i < lines.size) {
                    val declLine = collectDeclarationLine(lines, i)

                    // Check if this is a class/interface declaration
                    val declClassMatch = CLASS_PATTERN.find(declLine)
                    if (declClassMatch != null) {
                        val typeName = declClassMatch.groupValues[1]
                        typeStack.add(typeName)
                        typeAtDepth[braceDepth] = typeName
                        // Track braces in this line
                        for (ch in lines[i]) {
                            when (ch) { '{' -> braceDepth++; '}' -> braceDepth-- }
                        }
                        i++
                        continue
                    }

                    val parsed = parseDeclaration(declLine)
                    if (parsed != null) {
                        val kdoc = parseKDoc(kdocLines)
                        // If inside a class/interface and no explicit receiver, use enclosing type
                        val receiver = parsed.receiverType
                            ?: typeStack.lastOrNull()
                        results.add(
                            KDocEntry(
                                memberName = parsed.name,
                                receiverType = receiver,
                                paramCount = parsed.paramCount,
                                paramNames = parsed.paramNames,
                                summary = kdoc.summary,
                                description = kdoc.description,
                                paramDocs = kdoc.paramDocs,
                                returnDoc = kdoc.returnDoc,
                                since = kdoc.since,
                            )
                        )
                    }
                }
            } else {
                i++
            }
        }

        return results
    }

    // Collect enough of the declaration to parse the signature (handles multi-line params)
    private fun collectDeclarationLine(lines: List<String>, start: Int): String {
        val sb = StringBuilder()
        var depth = 0
        var foundOpen = false
        for (i in start until minOf(start + 20, lines.size)) {
            val line = lines[i]
            sb.append(line.trim()).append(" ")
            for (ch in line) {
                if (ch == '(') { foundOpen = true; depth++ }
                if (ch == ')') depth--
            }
            // Stop when we've closed all parens, or if it's a property/class (no parens)
            if (foundOpen && depth <= 0) break
            if (!foundOpen && (line.trimStart().startsWith("val ") || line.trimStart().startsWith("var "))) break
            if (!foundOpen && i > start) break
        }
        return sb.toString().trim()
    }

    private data class DeclInfo(
        val name: String,
        val receiverType: String?,
        val paramCount: Int,
        val paramNames: List<String>,
    )

    private val CLASS_PATTERN = Regex(
        """(?:public\s+|private\s+|protected\s+|internal\s+|open\s+|abstract\s+|sealed\s+|data\s+|expect\s+|actual\s+|value\s+|inner\s+|enum\s+)*(?:class|interface|object)\s+(\w+)"""
    )

    private val FUNC_PATTERN = Regex(
        """(?:public\s+|private\s+|protected\s+|internal\s+|open\s+|abstract\s+|override\s+|actual\s+|expect\s+|inline\s+|infix\s+|operator\s+|suspend\s+|tailrec\s+|external\s+|sealed\s+)*fun\s+(?:<[^>]+>\s+)?(?:(\S+)\.)?(\w+)\s*\("""
    )

    private val PROP_PATTERN = Regex(
        """(?:public\s+|private\s+|protected\s+|internal\s+|open\s+|abstract\s+|override\s+|actual\s+|expect\s+|const\s+|lateinit\s+)*(?:val|var)\s+(?:<[^>]+>\s+)?(?:(\S+)\.)?(\w+)\s*[:\n=]"""
    )

    private fun parseDeclaration(decl: String): DeclInfo? {
        // Try function
        val funcMatch = FUNC_PATTERN.find(decl)
        if (funcMatch != null) {
            val receiver = funcMatch.groupValues[1].takeIf { it.isNotEmpty() }?.simplifyType()
            val name = funcMatch.groupValues[2]
            val paramsStr = extractParamsString(decl, funcMatch.range.last)
            val paramNames = parseParamNames(paramsStr)
            return DeclInfo(name, receiver, paramNames.size, paramNames)
        }

        // Try property
        val propMatch = PROP_PATTERN.find(decl)
        if (propMatch != null) {
            val receiver = propMatch.groupValues[1].takeIf { it.isNotEmpty() }?.simplifyType()
            val name = propMatch.groupValues[2]
            return DeclInfo(name, receiver, 0, emptyList())
        }

        return null
    }

    private fun extractParamsString(decl: String, afterOpenParen: Int): String {
        // Find matching close paren
        var depth = 1
        val start = afterOpenParen + 1
        for (i in start until decl.length) {
            when (decl[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return decl.substring(start, i)
                }
            }
        }
        return decl.substring(start)
    }

    private fun parseParamNames(paramsStr: String): List<String> {
        val trimmed = paramsStr.trim()
        if (trimmed.isEmpty()) return emptyList()

        val names = mutableListOf<String>()
        var depth = 0
        var current = StringBuilder()

        for (ch in trimmed) {
            when {
                ch == '<' || ch == '(' -> { depth++; current.append(ch) }
                ch == '>' || ch == ')' -> { depth--; current.append(ch) }
                ch == ',' && depth == 0 -> {
                    extractParamName(current.toString())?.let { names.add(it) }
                    current = StringBuilder()
                }
                else -> current.append(ch)
            }
        }
        extractParamName(current.toString())?.let { names.add(it) }

        return names
    }

    private fun extractParamName(param: String): String? {
        val trimmed = param.trim()
        if (trimmed.isEmpty()) return null
        // Strip leading "vararg ", "noinline ", "crossinline "
        val cleaned = trimmed
            .removePrefix("vararg ")
            .removePrefix("noinline ")
            .removePrefix("crossinline ")
            .trim()
        // Name is before the ':'
        val colonIdx = cleaned.indexOf(':')
        return if (colonIdx > 0) cleaned.substring(0, colonIdx).trim() else null
    }

    private fun String.simplifyType(): String {
        // "Iterable<T>" → "Iterable", "Map<out K, V>" → "Map"
        val angleBracket = indexOf('<')
        val base = if (angleBracket > 0) substring(0, angleBracket) else this
        // "kotlin.collections.List" → "List"
        return base.substringAfterLast('.')
    }

    private fun countBraces(line: String, onBrace: (Int) -> Unit) {
        var inString = false
        var inChar = false
        var escaped = false
        for (ch in line) {
            if (escaped) { escaped = false; continue }
            if (ch == '\\') { escaped = true; continue }
            if (ch == '"' && !inChar) { inString = !inString; continue }
            if (ch == '\'' && !inString) { inChar = !inChar; continue }
            if (inString || inChar) continue
            when (ch) {
                '{' -> onBrace(1)
                '}' -> onBrace(-1)
            }
        }
    }

    // ── KDoc text parsing ───────────────────────
    private data class KDocContent(
        val summary: String,
        val description: String,
        val paramDocs: Map<String, String>,
        val returnDoc: String,
        val since: String,
    )

    private fun parseKDoc(lines: List<String>): KDocContent {
        // Strip comment markers
        val cleaned = lines.joinToString("\n")
            .replace(Regex("""/\*\*\s*"""), "")
            .replace(Regex("""\s*\*/"""), "")
            .lines()
            .map { it.trimStart().removePrefix("* ").removePrefix("*") }
            .joinToString("\n")
            .trim()

        val bodyLines = mutableListOf<String>()
        val paramDocs = mutableMapOf<String, String>()
        var returnDoc = ""
        var since = ""
        var currentTag: String? = null
        var currentTagContent = StringBuilder()

        fun flushTag() {
            when {
                currentTag == null -> {}
                currentTag!!.startsWith("param ") -> {
                    val paramName = currentTag!!.removePrefix("param ").trim().removePrefix("[").removeSuffix("]")
                    paramDocs[paramName] = currentTagContent.toString().trim()
                }
                currentTag == "return" -> returnDoc = currentTagContent.toString().trim()
                currentTag!!.startsWith("since") -> since = currentTagContent.toString().trim()
            }
        }

        for (line in cleaned.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("@param ") || trimmed.startsWith("@param[")) {
                flushTag()
                val rest = trimmed.removePrefix("@param").trim()
                // @param name desc  OR  @param [name] desc
                val match = Regex("""^\[?(\w+)]?\s*(.*)$""").find(rest)
                if (match != null) {
                    currentTag = "param ${match.groupValues[1]}"
                    currentTagContent = StringBuilder(match.groupValues[2])
                }
            } else if (trimmed.startsWith("@return ") || trimmed == "@return") {
                flushTag()
                currentTag = "return"
                currentTagContent = StringBuilder(trimmed.removePrefix("@return").trim())
            } else if (trimmed.startsWith("@since ")) {
                flushTag()
                currentTag = "since"
                currentTagContent = StringBuilder(trimmed.removePrefix("@since").trim())
            } else if (trimmed.startsWith("@")) {
                flushTag()
                currentTag = "other"
                currentTagContent = StringBuilder()
            } else if (currentTag != null) {
                currentTagContent.append(" ").append(trimmed)
            } else {
                bodyLines.add(line)
            }
        }
        flushTag()

        val fullBody = bodyLines.joinToString("\n").trim()
        // Summary = first sentence or first paragraph
        val summary = extractSummary(fullBody)
        // Description = everything after summary
        val description = fullBody

        return KDocContent(summary, description, paramDocs, returnDoc, since)
    }

    private fun extractSummary(text: String): String {
        if (text.isEmpty()) return ""
        // First paragraph (up to blank line) or first sentence (up to ". ")
        val firstPara = text.split("\n\n").first().replace('\n', ' ').trim()
        val periodIdx = firstPara.indexOf(". ")
        return if (periodIdx > 0 && periodIdx < 200) {
            firstPara.substring(0, periodIdx + 1)
        } else {
            firstPara
        }
    }
}
