package com.whatap.apk2project.fixer

import com.whatap.apk2project.utils.Logger
import java.io.File

/**
 * Graceful Degradation Fixer for heavily optimized/obfuscated APKs.
 *
 * When JADX produces syntactically invalid Java code (due to R8 optimizations),
 * this fixer keeps method signatures but replaces broken method bodies with TODO comments.
 *
 * This ensures the project can at least compile, even if some functionality is missing.
 */
class GracefulDegradationFixer {

    data class FixResult(
        val filesProcessed: Int,
        val methodsFixed: Int,
        val filesWithErrors: Int
    )

    // Patterns that indicate invalid JADX output (R8 optimization artifacts)
    private val invalidPatterns = listOf(
        // Empty cast: (Type) , or (Type) ;
        Regex("""\(\s*\w+\s*\)\s*[,;]"""),
        // Assignment without variable: = value;
        Regex("""^\s*=\s+[^=]"""),
        // Empty parentheses in wrong context: () ,
        Regex("""\(\s*\)\s*,"""),
        // Incomplete cast: (Type) followed by nothing valid
        Regex("""\(\s*\w+\s*\)\s*\)"""),
        // Code outside method: class, interface at wrong place
        Regex("""^\s*}\s*else\s+if\s*\("""),
        // Missing operand in expression
        Regex("""\(\s*[+\-*/&|^]\s*\w+\)"""),
        Regex("""\(\s*\w+\s*[+\-*/&|^]\s*\)"""),
    )

    /**
     * Fix all Java files in the source directory using graceful degradation.
     * This should be called AFTER the regular CodeFixer.
     */
    fun fixSourceDirectory(sourceDir: File): FixResult {
        var filesProcessed = 0
        var methodsFixed = 0
        var filesWithErrors = 0

        Logger.info("Applying graceful degradation fixes...")

        sourceDir.walkTopDown()
            .filter { it.extension == "java" }
            .forEach { file ->
                try {
                    val result = fixJavaFile(file)
                    if (result > 0) {
                        filesProcessed++
                        methodsFixed += result
                    }
                } catch (e: Exception) {
                    filesWithErrors++
                    Logger.debug("Error processing ${file.name}: ${e.message}")
                }
            }

        if (methodsFixed > 0) {
            Logger.warn("Graceful degradation: $methodsFixed methods stubbed in $filesProcessed files")
        }

        return FixResult(filesProcessed, methodsFixed, filesWithErrors)
    }

    /**
     * Fix a single Java file by detecting and stubbing broken methods.
     * Returns the number of methods fixed.
     */
    private fun fixJavaFile(file: File): Int {
        val content = file.readText()

        // Quick check: does this file have any invalid patterns?
        if (!hasInvalidPatterns(content)) {
            return 0
        }

        val fixedContent = fixBrokenMethods(content)

        if (fixedContent != content) {
            file.writeText(fixedContent)
            return countFixedMethods(content, fixedContent)
        }

        return 0
    }

    /**
     * Check if content contains any invalid patterns
     */
    private fun hasInvalidPatterns(content: String): Boolean {
        return invalidPatterns.any { it.containsMatchIn(content) } ||
               content.contains("class, interface, enum, or record expected") ||
               content.contains("illegal start of expression") ||
               content.contains("<identifier> expected")
    }

    /**
     * Fix broken methods by replacing their bodies with TODO comments.
     * Keeps method signatures intact for type checking.
     */
    private fun fixBrokenMethods(content: String): String {
        val lines = content.lines().toMutableList()
        var i = 0
        var modified = false

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // Detect method declaration
            if (isMethodDeclaration(trimmed)) {
                val methodStartLine = i
                val methodEndLine = findMethodEnd(lines, i)

                if (methodEndLine > methodStartLine) {
                    // Extract method body
                    val methodBody = lines.subList(methodStartLine, methodEndLine + 1).joinToString("\n")

                    // Check if method body has invalid patterns
                    if (hasInvalidPatterns(methodBody)) {
                        // Replace method body with stub
                        val stubbed = stubMethod(lines, methodStartLine, methodEndLine)
                        if (stubbed) {
                            modified = true
                            // Skip to after the stubbed method
                            i = methodStartLine + 3
                            continue
                        }
                    }
                }
            }
            i++
        }

        return if (modified) lines.joinToString("\n") else content
    }

    /**
     * Check if a line is a method declaration
     */
    private fun isMethodDeclaration(line: String): Boolean {
        // Skip constructors, fields, and class declarations
        if (line.startsWith("class ") || line.startsWith("interface ") ||
            line.startsWith("enum ") || line.contains(" class ") ||
            line.contains(" interface ") || line.contains(" enum ")) {
            return false
        }

        // Method pattern: modifiers + return_type + name + (params) + { or throws
        val methodPattern = Regex(
            """^(public|private|protected|static|final|synchronized|native|abstract|\s)+""" +
            """[\w<>\[\],\s]+\s+\w+\s*\([^)]*\)\s*(throws\s+[\w,\s]+)?\s*\{"""
        )

        return methodPattern.containsMatchIn(line)
    }

    /**
     * Find the end line of a method (matching closing brace)
     */
    private fun findMethodEnd(lines: List<String>, startLine: Int): Int {
        var braceCount = 0
        var foundOpenBrace = false

        for (i in startLine until lines.size) {
            val line = lines[i]

            // Skip strings and comments for brace counting
            var inString = false
            var inChar = false
            var j = 0

            while (j < line.length) {
                val c = line[j]

                // Handle escape sequences
                if ((inString || inChar) && c == '\\' && j + 1 < line.length) {
                    j += 2
                    continue
                }

                // Handle strings
                if (c == '"' && !inChar) {
                    inString = !inString
                }

                // Handle char literals
                if (c == '\'' && !inString) {
                    inChar = !inChar
                }

                // Count braces outside strings
                if (!inString && !inChar) {
                    when (c) {
                        '{' -> {
                            braceCount++
                            foundOpenBrace = true
                        }
                        '}' -> braceCount--
                    }
                }

                j++
            }

            // Found matching closing brace
            if (foundOpenBrace && braceCount == 0) {
                return i
            }

            // Safety: don't go beyond 500 lines for a single method
            if (i - startLine > 500) {
                return -1
            }
        }

        return -1
    }

    /**
     * Replace method body with a stub
     */
    private fun stubMethod(lines: MutableList<String>, startLine: Int, endLine: Int): Boolean {
        val methodDecl = lines[startLine]
        val indent = methodDecl.takeWhile { it.isWhitespace() }

        // Extract method signature
        val signature = extractMethodSignature(methodDecl)
        if (signature == null) {
            return false
        }

        // Determine return type and appropriate return statement
        val returnType = extractReturnType(methodDecl)
        val returnStatement = getDefaultReturn(returnType)

        // Build stubbed method
        val stubbedLines = mutableListOf<String>()
        stubbedLines.add(methodDecl.substringBefore("{").trimEnd() + " {")
        stubbedLines.add("$indent    // TODO: Decompilation failed - method body could not be restored")
        stubbedLines.add("$indent    // Original method contained invalid bytecode patterns (R8 optimization)")
        if (returnStatement.isNotEmpty()) {
            stubbedLines.add("$indent    $returnStatement")
        }
        stubbedLines.add("$indent}")

        // Replace original method lines
        for (i in endLine downTo startLine) {
            lines.removeAt(i)
        }

        for ((index, stubbedLine) in stubbedLines.withIndex()) {
            lines.add(startLine + index, stubbedLine)
        }

        return true
    }

    /**
     * Extract method signature from declaration line
     */
    private fun extractMethodSignature(line: String): String? {
        val match = Regex("""(\w+)\s*\([^)]*\)""").find(line)
        return match?.value
    }

    /**
     * Extract return type from method declaration
     */
    private fun extractReturnType(line: String): String {
        // Remove modifiers and find the type before method name
        val withoutModifiers = line
            .replace(Regex("""(public|private|protected|static|final|synchronized|native|abstract)\s+"""), "")
            .trim()

        // Find the return type (word before method name and parenthesis)
        val match = Regex("""([\w<>\[\],\s]+)\s+\w+\s*\(""").find(withoutModifiers)
        return match?.groupValues?.get(1)?.trim() ?: "void"
    }

    /**
     * Get default return statement based on return type
     */
    private fun getDefaultReturn(returnType: String): String {
        return when {
            returnType == "void" -> ""
            returnType == "boolean" -> "return false;"
            returnType in listOf("int", "long", "short", "byte", "char") -> "return 0;"
            returnType in listOf("float", "double") -> "return 0.0;"
            returnType.endsWith("[]") -> "return null;"
            else -> "return null;"
        }
    }

    /**
     * Count how many methods were fixed
     */
    private fun countFixedMethods(original: String, fixed: String): Int {
        val originalTodos = original.split("// TODO: Decompilation failed").size - 1
        val fixedTodos = fixed.split("// TODO: Decompilation failed").size - 1
        return fixedTodos - originalTodos
    }

    /**
     * Alternative approach: Delete files with too many errors instead of trying to fix them.
     * This is more aggressive but ensures the project compiles.
     */
    fun deleteProblematicFiles(sourceDir: File, errorThreshold: Int = 10): Int {
        var deletedCount = 0

        sourceDir.walkTopDown()
            .filter { it.extension == "java" }
            .forEach { file ->
                try {
                    val content = file.readText()
                    val errorCount = countInvalidPatterns(content)

                    if (errorCount >= errorThreshold) {
                        file.delete()
                        deletedCount++
                        Logger.debug("Deleted ${file.name} (${errorCount} errors)")
                    }
                } catch (e: Exception) {
                    // Ignore errors
                }
            }

        if (deletedCount > 0) {
            Logger.warn("Deleted $deletedCount files with excessive decompilation errors")
        }

        return deletedCount
    }

    /**
     * Count invalid patterns in content
     */
    private fun countInvalidPatterns(content: String): Int {
        var count = 0
        for (pattern in invalidPatterns) {
            count += pattern.findAll(content).count()
        }
        // Also count common compiler error indicators in JADX output
        count += content.split("class, interface, enum, or record expected").size - 1
        count += content.split("illegal start of").size - 1
        return count
    }
}
