package com.whatap.apk2project.fixer

import com.whatap.apk2project.utils.Logger
import java.io.File

/**
 * Post-processor for decompiled Java code to fix common compilation errors.
 */
class CodeFixer {

    private var fixedFiles = 0
    private var totalFixes = 0

    data class FixResult(
        val fixedFiles: Int,
        val totalFixes: Int,
        val stubsGenerated: Int
    )

    data class MethodSignature(val name: String, val argCount: Int)

    /**
     * Fix all Java files in the source directory
     */
    fun fixSourceDirectory(sourceDir: File): FixResult {
        fixedFiles = 0
        totalFixes = 0
        val missingMethods = mutableSetOf<MethodSignature>()

        Logger.info("Scanning for compilation errors...")

        // First pass: find all missing methods
        sourceDir.walkTopDown()
            .filter { it.extension == "java" }
            .forEach { file ->
                missingMethods.addAll(findMissingMethods(file))
            }

        // Second pass: fix code and collect all fixes
        sourceDir.walkTopDown()
            .filter { it.extension == "java" }
            .forEach { file ->
                if (fixJavaFile(file)) {
                    fixedFiles++
                }
            }

        // Generate stub class for missing obfuscated methods
        val stubsGenerated = if (missingMethods.isNotEmpty()) {
            generateStubClass(sourceDir, missingMethods)
            1
        } else 0

        Logger.success("Fixed $totalFixes issues in $fixedFiles files")
        if (stubsGenerated > 0) {
            Logger.info("Generated stub class with ${missingMethods.size} method stubs")
        }

        return FixResult(fixedFiles, totalFixes, stubsGenerated)
    }

    /**
     * Fix common decompilation errors in a single Java file
     */
    private fun fixJavaFile(file: File): Boolean {
        var content = file.readText()
        val originalContent = content
        var fileFixCount = 0

        // Fix 0: Fix JADX ?? syntax (unknown type/register)
        val fix0 = fixUnknownTypeSyntax(content)
        if (fix0 != content) {
            content = fix0
            fileFixCount++
        }

        // Fix 1: Bitwise operation type mismatches
        val fix1 = fixBitwiseOperations(content)
        if (fix1 != content) {
            content = fix1
            fileFixCount++
        }

        // Fix 2: Comment out unreachable code after return/throw
        val fix2 = fixUnreachableCode(content)
        if (fix2 != content) {
            content = fix2
            fileFixCount++
        }

        // Fix 3: Fix malformed method calls with wrong number of arguments
        val fix3 = fixMalformedCalls(content)
        if (fix3 != content) {
            content = fix3
            fileFixCount++
        }

        // Fix 4: Add missing imports for common types
        val fix4 = addMissingImports(content)
        if (fix4 != content) {
            content = fix4
            fileFixCount++
        }

        // Fix 5: Fix incomplete method calls with JADX ERROR
        val fix5 = fixIncompleteMethodCallWithJadxError(content)
        if (fix5 != content) {
            content = fix5
            fileFixCount++
        }

        // Fix 6: Fix interface static fields without initializers
        val fix6 = fixInterfaceStaticFields(content)
        if (fix6 != content) {
            content = fix6
            fileFixCount++
        }

        // Fix 7: Fix static initializer blocks in interfaces
        val fix7 = fixInterfaceStaticInitializer(content)
        if (fix7 != content) {
            content = fix7
            fileFixCount++
        }

        // Fix 8: Comment out orphaned code after JADX FIX comment
        val fix8 = fixOrphanedCodeAfterJadxComment(content)
        if (fix8 != content) {
            content = fix8
            fileFixCount++
        }

        // Fix 9: Fix int == true comparisons (should be != 0)
        val fix9 = fixIntBooleanComparison(content)
        if (fix9 != content) {
            content = fix9
            fileFixCount++
        }

        // Fix 10: Fix ternary with boolean result assigned to int
        val fix10 = fixTernaryTypeMismatch(content)
        if (fix10 != content) {
            content = fix10
            fileFixCount++
        }

        // Fix 11: Fix missing variable declarations
        val fix11 = fixMissingVariableDeclarations(content)
        if (fix11 != content) {
            content = fix11
            fileFixCount++
        }

        // Fix 12: Add missing closing braces at end of file (must run LAST)
        val fix12 = addMissingClosingBraces(content)
        if (fix12 != content) {
            content = fix12
            fileFixCount++
        }

        if (content != originalContent) {
            file.writeText(content)
            totalFixes += fileFixCount
            return true
        }

        return false
    }

    private fun fixUnknownTypeSyntax(content: String): String {
        var result = content
        result = result.replace(Regex("""\?\?\s+(\w+)\s*;"""), "Object $1;")
        result = result.replace(Regex("""\?\?\s+(\w+)\s*=\s*([^;]+);"""), "Object $1 = $2;")
        result = result.replace(Regex("""^\s*\?\?\s*[^;]*;?\s*$""", RegexOption.MULTILINE), "// JADX FIX: removed unknown type")
        return result
    }

    private fun fixBitwiseOperations(content: String): String {
        var result = content
        val bitwisePattern = Regex("""(\w+)\s*=\s*\((\w+)\s*[&|]\s*\d+\)\s*[+\-*/]\s*\((\w+)\s*[&|]\s*\d+\);""")
        result = bitwisePattern.replace(result) { match ->
            val varName = match.groupValues[1]
            val expr = match.value.substringAfter("=").substringBeforeLast(";").trim()
            "$varName = (int)($expr);"
        }
        return result
    }

    private fun fixUnreachableCode(content: String): String {
        val lines = content.lines().toMutableList()
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()

            if ((line.startsWith("return ") || line.startsWith("return;") ||
                 line.startsWith("throw ")) && line.endsWith(";")) {
                var j = i + 1
                while (j < lines.size) {
                    val nextLine = lines[j].trim()
                    if (nextLine.isEmpty() || nextLine.startsWith("//")) {
                        j++
                        continue
                    }
                    if (nextLine.startsWith("}") || nextLine.startsWith("else") ||
                        nextLine.startsWith("case ") || nextLine.startsWith("default:") ||
                        nextLine.startsWith("catch") || nextLine.startsWith("finally")) {
                        break
                    }
                    if (!lines[j].trim().startsWith("//")) {
                        lines[j] = "// UNREACHABLE: " + lines[j]
                    }
                    j++
                }
            }
            i++
        }

        return lines.joinToString("\n")
    }

    private fun fixMalformedCalls(content: String): String {
        var result = content
        result = result.replace(Regex("""\(\s*,\s*\)"""), "()")
        result = result.replace(Regex("""\(\s*,"""), "(null,")
        result = result.replace(Regex(""",\s*\)"""), ")")
        return result
    }

    private fun addMissingImports(content: String): String {
        if (!content.contains("package ")) return content

        val neededImports = mutableListOf<String>()

        val importChecks = mapOf(
            "Bitmap" to "android.graphics.Bitmap",
            "Bundle" to "android.os.Bundle",
            "Intent" to "android.content.Intent",
            "Context" to "android.content.Context",
            "View" to "android.view.View",
            "TextView" to "android.widget.TextView",
            "ImageView" to "android.widget.ImageView",
            "Activity" to "android.app.Activity",
            "Fragment" to "androidx.fragment.app.Fragment",
            "Log" to "android.util.Log",
            "Uri" to "android.net.Uri",
            "Handler" to "android.os.Handler",
            "Looper" to "android.os.Looper"
        )

        for ((className, importPath) in importChecks) {
            if (content.contains(className) && !content.contains("import $importPath") &&
                !content.contains("import android.graphics.*") &&
                !content.contains("import ${importPath.substringBeforeLast(".")}.*")) {
                neededImports.add("import $importPath;")
            }
        }

        if (neededImports.isEmpty()) return content

        val packageEnd = content.indexOf(";", content.indexOf("package ")) + 1
        val beforeImports = content.substring(0, packageEnd)
        val afterPackage = content.substring(packageEnd)

        return beforeImports + "\n\n" + neededImports.joinToString("\n") + afterPackage
    }

    private fun fixIncompleteMethodCallWithJadxError(content: String): String {
        val lines = content.lines().toMutableList()
        var i = 0

        while (i < lines.size - 1) {
            val line = lines[i]
            val trimmed = line.trim()

            if (trimmed.matches(Regex(""".*\w+\.\w+\s*\(\s*$""")) ||
                trimmed.matches(Regex(""".*\w+\s*\(\s*$"""))) {
                var j = i + 1
                var foundJadxError = false
                var throwLineIndex = -1

                while (j < lines.size && j < i + 100) {
                    val nextLine = lines[j].trim()
                    if (nextLine.contains("JADX ERROR:") || nextLine.contains("JADX WARN:")) {
                        foundJadxError = true
                    }
                    if (foundJadxError && nextLine.startsWith("throw new UnsupportedOperationException")) {
                        throwLineIndex = j
                        break
                    }
                    if (nextLine.startsWith("*/") && !foundJadxError) {
                        break
                    }
                    j++
                }

                if (foundJadxError && throwLineIndex > 0) {
                    val indent = line.takeWhile { it.isWhitespace() }
                    lines[i] = "$indent// JADX FIX: Incomplete method call removed"

                    for (k in (i + 1) until throwLineIndex) {
                        if (!lines[k].trim().startsWith("//") && lines[k].trim().isNotEmpty()) {
                            val lineIndent = lines[k].takeWhile { it.isWhitespace() }
                            lines[k] = "$lineIndent// ${lines[k].trim()}"
                        }
                    }

                    i = throwLineIndex
                }
            }
            i++
        }

        return lines.joinToString("\n")
    }

    private fun fixInterfaceStaticFields(content: String): String {
        if (!content.contains("public interface ") && !content.contains("\ninterface ")) {
            return content
        }

        var result = content

        result = result.replace(
            Regex("""(public\s+)?static\s+final\s+String\s+(\w+)\s*;""")
        ) { match ->
            val modifier = match.groupValues[1]
            val fieldName = match.groupValues[2]
            "${modifier}static final String $fieldName = \"\";"
        }

        result = result.replace(
            Regex("""(public\s+)?static\s+final\s+(\w+)\s+(\w+)\s*;""")
        ) { match ->
            val modifier = match.groupValues[1]
            val type = match.groupValues[2]
            val fieldName = match.groupValues[3]

            val defaultValue = when (type) {
                "int", "long", "short", "byte" -> "0"
                "float" -> "0.0f"
                "double" -> "0.0"
                "boolean" -> "false"
                "char" -> "'\\0'"
                else -> "null"
            }
            "${modifier}static final $type $fieldName = $defaultValue;"
        }

        return result
    }

    private fun fixInterfaceStaticInitializer(content: String): String {
        if (!content.contains("public interface ") && !content.contains("\ninterface ")) {
            return content
        }

        val lines = content.lines().toMutableList()
        var i = 0
        var inStaticBlock = false
        var staticBlockBraceCount = 0
        var inInterface = false
        var interfaceBraceCount = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // Track interface entry/exit
            if (!inStaticBlock) {
                // Check for interface declaration
                if (trimmed.matches(Regex("""^(public\s+)?interface\s+\w+.*\{.*"""))) {
                    inInterface = true
                    interfaceBraceCount = 1
                    // Count braces on the same line
                    for (c in trimmed.substring(trimmed.indexOf('{'))) {
                        when (c) {
                            '{' -> interfaceBraceCount++
                            '}' -> interfaceBraceCount--
                        }
                    }
                    interfaceBraceCount--
                } else if (inInterface && !inStaticBlock) {
                    // Track braces to know when we exit the interface
                    for (c in trimmed) {
                        when (c) {
                            '{' -> interfaceBraceCount++
                            '}' -> interfaceBraceCount--
                        }
                    }
                    if (interfaceBraceCount <= 0) {
                        inInterface = false
                    }
                }
            }

            // Only fix static initializers INSIDE interfaces
            if (inInterface && !inStaticBlock && trimmed.matches(Regex("""static\s*\{.*"""))) {
                inStaticBlock = true
                staticBlockBraceCount = 1
                for (c in trimmed.substring(trimmed.indexOf('{'))) {
                    when (c) {
                        '{' -> staticBlockBraceCount++
                        '}' -> staticBlockBraceCount--
                    }
                }
                staticBlockBraceCount--

                val indent = line.takeWhile { it.isWhitespace() }
                lines[i] = "$indent// JADX FIX: Static initializer not allowed in interface"

                if (staticBlockBraceCount <= 0) {
                    inStaticBlock = false
                }
            } else if (inStaticBlock) {
                val indent = line.takeWhile { it.isWhitespace() }
                lines[i] = "$indent// $trimmed"

                for (c in trimmed) {
                    when (c) {
                        '{' -> staticBlockBraceCount++
                        '}' -> staticBlockBraceCount--
                    }
                }

                if (staticBlockBraceCount <= 0) {
                    inStaticBlock = false
                }
            }
            i++
        }

        return lines.joinToString("\n")
    }

    private fun findMissingMethods(file: File): Set<MethodSignature> {
        val content = file.readText()
        val methods = mutableSetOf<MethodSignature>()

        val patterns = listOf(
            Regex("""(?<![.\w])([a-z]{2,3})\s*\(\s*(\d+)\s*,([^)]+)\)"""),
            Regex("""(?<![.\w])(m[o]?\d+\w*)\s*\(([^)]*)\)""")
        )

        for (pattern in patterns) {
            pattern.findAll(content).forEach { match ->
                val methodName = match.groupValues[1]
                val args = if (match.groupValues.size > 2) match.groupValues.last() else ""
                val argCount = if (args.isBlank()) 0 else args.split(",").size +
                    (if (match.groupValues.size > 2 && match.groupValues[2].isNotBlank()) 1 else 0)

                methods.add(MethodSignature(methodName, argCount.coerceAtMost(10)))
            }
        }

        return methods
    }

    private fun generateStubClass(sourceDir: File, methods: Set<MethodSignature>) {
        val stubDir = File(sourceDir, "decompiler_stubs")
        stubDir.mkdirs()

        val stubContent = buildString {
            appendLine("package decompiler_stubs;")
            appendLine()
            appendLine("/**")
            appendLine(" * Auto-generated stub class for missing obfuscated methods.")
            appendLine(" */")
            appendLine("@SuppressWarnings(\"unused\")")
            appendLine("public class DecompilerStubs {")
            appendLine()

            val methodsByName = methods.groupBy { it.name }

            for ((name, overloads) in methodsByName.toSortedMap()) {
                for (sig in overloads.sortedBy { it.argCount }) {
                    val params = (0 until sig.argCount).joinToString(", ") { "Object arg$it" }
                    appendLine("    public static Object $name($params) {")
                    appendLine("        throw new UnsupportedOperationException(\"Stub method: $name\");")
                    appendLine("    }")
                    appendLine()
                }
            }

            appendLine("}")
        }

        File(stubDir, "DecompilerStubs.java").writeText(stubContent)

        Logger.debug("Generated stub class with ${methods.size} method stubs")
    }

    /**
     * Comment out orphaned code that appears after "// JADX FIX: Static initializer" comment.
     * JADX sometimes comments out part of static initializer but leaves remaining code exposed.
     *
     * Pattern: Code after "// JADX FIX: Static initializer not allowed in interface" comment
     * Solution: Comment out all code until next method or class member (at class level, not inside anonymous classes)
     */
    private fun fixOrphanedCodeAfterJadxComment(content: String): String {
        val lines = content.lines().toMutableList()
        var modified = false
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // Look for JADX FIX comment about static initializer
            if (trimmed.startsWith("// JADX FIX: Static initializer")) {
                var j = i + 1
                var braceDepth = 0
                var anonymousClassDepth = 0  // Track anonymous class nesting

                // Comment out all following lines until we hit a method declaration AT CLASS LEVEL
                while (j < lines.size) {
                    val nextLine = lines[j]
                    val nextTrimmed = nextLine.trim()

                    // Skip empty lines
                    if (nextTrimmed.isEmpty()) {
                        j++
                        continue
                    }

                    // Skip already commented lines (but track ORPHANED comments for brace counting)
                    if (nextTrimmed.startsWith("//")) {
                        // Count braces in ORPHANED lines for tracking anonymous class depth
                        if (nextTrimmed.startsWith("// ORPHANED:")) {
                            val orphanedContent = nextTrimmed.substringAfter("// ORPHANED:").trim()
                            // Check if this line starts an anonymous class
                            if (orphanedContent.contains("new ") && orphanedContent.contains("()") && orphanedContent.contains("{")) {
                                anonymousClassDepth++
                            }
                            for (c in orphanedContent) {
                                when (c) {
                                    '{' -> braceDepth++
                                    '}' -> {
                                        braceDepth--
                                        if (anonymousClassDepth > 0 && braceDepth < anonymousClassDepth) {
                                            anonymousClassDepth--
                                        }
                                    }
                                }
                            }
                        }
                        j++
                        continue
                    }

                    // Check if this line starts an anonymous class (new Something() { or new Something() { ... })
                    if (nextTrimmed.contains("new ") && nextTrimmed.contains("()") && nextTrimmed.contains("{")) {
                        anonymousClassDepth++
                    }

                    // Only stop at method declarations if we're at class level (not inside anonymous class)
                    if (anonymousClassDepth == 0 && braceDepth <= 0) {
                        // Stop at method declarations (with proper signature)
                        if (nextTrimmed.matches(Regex("""^(public|private|protected)\s+(static\s+)?(final\s+)?(synchronized\s+)?(native\s+)?[\w<>\[\],\s]+\s+\w+\s*\([^)]*\)\s*(throws\s+[\w,\s]+)?\s*\{.*"""))) {
                            break
                        }

                        // Stop at constructor
                        if (nextTrimmed.matches(Regex("""^(public|private|protected)\s+\w+\s*\([^)]*\)\s*\{.*"""))) {
                            break
                        }

                        // Stop at class/interface declarations
                        if (nextTrimmed.matches(Regex("""^(public|private|protected)?\s*(static)?\s*(final)?\s*(class|interface|enum)\s+.*"""))) {
                            break
                        }
                    }

                    // Stop at JADX WARN comments (start of new section)
                    if (nextTrimmed.startsWith("/* JADX")) {
                        break
                    }

                    // Track braces BEFORE deciding to stop
                    for (c in nextTrimmed) {
                        when (c) {
                            '{' -> braceDepth++
                            '}' -> {
                                braceDepth--
                                // If we close an anonymous class
                                if (anonymousClassDepth > 0 && braceDepth < anonymousClassDepth) {
                                    anonymousClassDepth--
                                }
                            }
                        }
                    }

                    // If this is a closing brace and we're at negative depth (no more anonymous classes)
                    // and we're at class level, this might be the static block end
                    if (nextTrimmed == "}" && braceDepth < 0 && anonymousClassDepth == 0) {
                        val lineIndent = nextLine.takeWhile { it.isWhitespace() }
                        lines[j] = "$lineIndent// ORPHANED: $nextTrimmed"
                        modified = true
                        j++
                        break
                    }

                    // Comment out this line
                    val lineIndent = nextLine.takeWhile { it.isWhitespace() }
                    lines[j] = "$lineIndent// ORPHANED: $nextTrimmed"
                    modified = true
                    j++
                }
                i = j
            } else {
                i++
            }
        }

        return if (modified) lines.joinToString("\n") else content
    }

    /**
     * Fix orphaned code that appears outside methods.
     * This happens when JADX fails to properly restore static initializer blocks.
     *
     * Pattern: Code statements (like while, if, assignments) appearing at class level
     * Solution: Wrap them in a static { } block
     */
    private fun fixOrphanedCode(content: String): String {
        val lines = content.lines().toMutableList()
        var i = 0
        var classDepth = 0
        var inMethod = false
        var methodBraceDepth = 0
        var modified = false

        // Track orphaned code regions
        data class OrphanedRegion(val startLine: Int, val endLine: Int, val indent: String)
        val orphanedRegions = mutableListOf<OrphanedRegion>()

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // Track class depth
            if (trimmed.matches(Regex("""^(public\s+|private\s+|protected\s+)?(static\s+)?(final\s+)?(class|interface|enum)\s+.*\{.*"""))) {
                classDepth++
            }

            // Track method entry
            if (classDepth > 0 && !inMethod && trimmed.matches(Regex("""^(public|private|protected|static|final|synchronized|native|abstract|\s)+[\w<>\[\],\s]+\s+\w+\s*\([^)]*\)\s*(throws\s+[\w,\s]+)?\s*\{.*"""))) {
                inMethod = true
                methodBraceDepth = 1
                i++
                continue
            }

            // Track braces
            if (inMethod) {
                for (c in trimmed) {
                    when (c) {
                        '{' -> methodBraceDepth++
                        '}' -> methodBraceDepth--
                    }
                }
                if (methodBraceDepth <= 0) {
                    inMethod = false
                }
            }

            // Detect orphaned code (statements at class level, not in a method)
            if (classDepth > 0 && !inMethod && trimmed.isNotEmpty() && !trimmed.startsWith("//")) {
                // Patterns that indicate orphaned executable code
                val isOrphanedCode = trimmed.matches(Regex("""^(while|if|for|switch|try)\s*\(.*""")) ||
                    trimmed.matches(Regex("""^[a-zA-Z_]\w*\s*=\s*.+;$""")) ||  // Assignment
                    trimmed.matches(Regex("""^[a-zA-Z_]\w*\s*\.\s*\w+\s*\(.*""")) ||  // Method call
                    trimmed.matches(Regex("""^(new\s+\w+|[\w.]+\s*\().*""")) ||  // new or method call
                    (trimmed.matches(Regex("""^\w+\s+\w+\s*=.*""")) && !trimmed.contains("static") && !trimmed.contains("final"))  // Variable init

                if (isOrphanedCode) {
                    val indent = line.takeWhile { it.isWhitespace() }
                    var endLine = i

                    // Find the end of this orphaned block
                    var braceCount = 0
                    for (j in i until lines.size) {
                        val checkLine = lines[j].trim()
                        for (c in checkLine) {
                            when (c) {
                                '{' -> braceCount++
                                '}' -> braceCount--
                            }
                        }
                        endLine = j
                        if (braceCount <= 0 && (checkLine.endsWith(";") || checkLine.endsWith("}"))) {
                            break
                        }
                    }

                    orphanedRegions.add(OrphanedRegion(i, endLine, indent))
                    i = endLine + 1
                    continue
                }
            }

            // Track class exit
            if (trimmed == "}" && classDepth > 0 && !inMethod) {
                classDepth--
            }

            i++
        }

        // Apply fixes in reverse order to preserve line numbers
        for (region in orphanedRegions.reversed()) {
            val indent = region.indent
            lines.add(region.endLine + 1, "$indent}")
            lines.add(region.startLine, "$indent// JADX FIX: Wrapped orphaned code in static block")
            lines.add(region.startLine + 1, "${indent}static {")
            modified = true
        }

        return if (modified) lines.joinToString("\n") else content
    }

    /**
     * Fix comparisons like: i6 == true or i6 == false
     * In Java, you cannot compare int to boolean.
     *
     * Pattern: varName == true -> varName != 0
     * Pattern: varName == false -> varName == 0
     */
    private fun fixIntBooleanComparison(content: String): String {
        var result = content

        // Fix: variable == true -> variable != 0
        result = result.replace(Regex("""(\w+)\s*==\s*true"""), "$1 != 0")

        // Fix: variable == false -> variable == 0
        result = result.replace(Regex("""(\w+)\s*==\s*false"""), "$1 == 0")

        // Fix: variable != true -> variable == 0
        result = result.replace(Regex("""(\w+)\s*!=\s*true"""), "$1 == 0")

        // Fix: variable != false -> variable != 0
        result = result.replace(Regex("""(\w+)\s*!=\s*false"""), "$1 != 0")

        return result
    }

    /**
     * Fix ternary expressions where boolean result is assigned to int.
     *
     * Pattern: intVar = condition ? 1 : 0; (this is OK)
     * Pattern: s10 = i14 == true ? 1 : 0; -> s10 = (i14 != 0) ? 1 : 0;
     */
    private fun fixTernaryTypeMismatch(content: String): String {
        var result = content

        // Fix: (condition == true ? 1 : 0) -> ((condition != 0) ? 1 : 0)
        result = result.replace(
            Regex("""(\w+)\s*=\s*(\w+)\s*==\s*true\s*\?\s*1\s*:\s*0"""),
            "$1 = ($2 != 0) ? 1 : 0"
        )

        // Fix: (int) cast on boolean expression result
        result = result.replace(
            Regex("""\(int\)\s*\(\s*(\w+)\s*==\s*true\s*\?\s*1\s*:\s*0\s*\)"""),
            "(($1 != 0) ? 1 : 0)"
        )

        return result
    }

    /**
     * Fix missing variable declarations.
     * When JADX fails to properly restore static blocks, variable declarations
     * might be commented out but their usages remain.
     *
     * Pattern: iArr[x] = y; without iArr being declared
     * Solution: Add declaration before first use
     */
    private fun fixMissingVariableDeclarations(content: String): String {
        val lines = content.lines().toMutableList()
        var modified = false

        // Find variables that are used but not declared
        val declaredVars = mutableSetOf<String>()
        val usedArrayVars = mutableMapOf<String, Int>()  // varName -> first usage line

        // First pass: find all declared variables
        val declPattern = Regex("""(int|long|short|byte|float|double|char|boolean|String|Object)\s*\[\s*\]\s*(\w+)\s*=""")
        val declPattern2 = Regex("""(int|long|short|byte|float|double|char|boolean|String|Object)\s+(\w+)\s*=""")
        val declPattern3 = Regex("""new\s+(int|long|short|byte|float|double|char|boolean|String|Object)\s*\[\s*[^]]+\s*\]""")

        for (line in lines) {
            declPattern.findAll(line).forEach { declaredVars.add(it.groupValues[2]) }
            declPattern2.findAll(line).forEach { declaredVars.add(it.groupValues[2]) }
        }

        // Second pass: find array usages without declarations
        val arrayUsagePattern = Regex("""(\w+)\s*\[\s*\w+\s*\]\s*=""")
        for ((index, line) in lines.withIndex()) {
            arrayUsagePattern.findAll(line).forEach { match ->
                val varName = match.groupValues[1]
                if (varName !in declaredVars && varName !in usedArrayVars) {
                    usedArrayVars[varName] = index
                }
            }
        }

        // Add declarations for missing array variables
        for ((varName, lineIndex) in usedArrayVars) {
            val indent = lines[lineIndex].takeWhile { it.isWhitespace() }
            // Find the array size from commented code if possible
            val commentedDecl = lines.take(lineIndex).lastOrNull {
                it.contains("// ") && it.contains("$varName") && it.contains("new int[")
            }

            val sizeExpr = if (commentedDecl != null) {
                val match = Regex("""new\s+int\s*\[\s*([^]]+)\s*\]""").find(commentedDecl)
                match?.groupValues?.get(1)?.replace(Regex("""\([^)]+\)\.length\(\)"""), "256") ?: "256"
            } else {
                "256"  // Default size
            }

            lines.add(lineIndex, "$indent// JADX FIX: Added missing array declaration")
            lines.add(lineIndex + 1, "${indent}int[] $varName = new int[$sizeExpr];")
            modified = true
        }

        return if (modified) lines.joinToString("\n") else content
    }

    /**
     * Add missing closing braces at the end of file.
     * Simple and safe: just count braces and add any missing ones.
     * This fixes "reached end of file while parsing" errors.
     */
    private fun addMissingClosingBraces(content: String): String {
        // Count braces - be careful to skip braces inside strings and comments
        var braceBalance = 0
        var inString = false
        var inChar = false
        var inLineComment = false
        var inBlockComment = false
        var escapeNext = false
        var i = 0

        while (i < content.length) {
            val c = content[i]
            val nextC = if (i + 1 < content.length) content[i + 1] else ' '

            if (escapeNext) {
                escapeNext = false
                i++
                continue
            }

            if (c == '\\' && (inString || inChar)) {
                escapeNext = true
                i++
                continue
            }

            // Handle newline - ends line comment
            if (c == '\n') {
                inLineComment = false
                i++
                continue
            }

            // Skip if in comment
            if (inLineComment) {
                i++
                continue
            }

            // Check for block comment end
            if (inBlockComment) {
                if (c == '*' && nextC == '/') {
                    inBlockComment = false
                    i += 2
                    continue
                }
                i++
                continue
            }

            // Check for comment start
            if (!inString && !inChar && c == '/') {
                if (nextC == '/') {
                    inLineComment = true
                    i += 2
                    continue
                }
                if (nextC == '*') {
                    inBlockComment = true
                    i += 2
                    continue
                }
            }

            // Handle strings
            if (!inChar && c == '"' && !inString) {
                inString = true
                i++
                continue
            }
            if (inString && c == '"') {
                inString = false
                i++
                continue
            }

            // Handle char literals
            if (!inString && c == '\'' && !inChar) {
                inChar = true
                i++
                continue
            }
            if (inChar && c == '\'') {
                inChar = false
                i++
                continue
            }

            // Count braces (only if not in string/char/comment)
            if (!inString && !inChar) {
                when (c) {
                    '{' -> braceBalance++
                    '}' -> braceBalance--
                }
            }

            i++
        }

        // If braceBalance > 0, we need closing braces
        if (braceBalance > 0) {
            val sb = StringBuilder(content.trimEnd())
            sb.append("\n")
            repeat(braceBalance) {
                sb.append("}\n")
            }
            return sb.toString()
        }

        return content
    }

    /**
     * Fix commented-out class closing braces.
     * When fixOrphanedCodeAfterJadxComment runs, it may accidentally comment out
     * the closing brace of the class, causing "reached end of file while parsing" errors.
     *
     * Pattern: // ORPHANED: } or // } at end of file
     * Solution: Restore the closing brace
     */
    @Suppress("unused")
    private fun fixCommentedClosingBrace(content: String): String {
        val lines = content.lines().toMutableList()
        var modified = false

        // Count actual braces to determine if we're missing closing braces
        var braceBalance = 0
        for (line in lines) {
            val trimmed = line.trim()
            // Skip completely commented lines for counting real structure
            if (trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                continue
            }
            for (c in trimmed) {
                when (c) {
                    '{' -> braceBalance++
                    '}' -> braceBalance--
                }
            }
        }

        // If braceBalance > 0, we need closing braces
        // Look for commented closing braces to restore
        if (braceBalance > 0) {
            // Scan from bottom to find commented closing braces
            var i = lines.size - 1
            var bracesToRestore = braceBalance

            while (i >= 0 && bracesToRestore > 0) {
                val line = lines[i]
                val trimmed = line.trim()

                // Check for commented closing brace patterns
                if (trimmed == "// ORPHANED: }" || trimmed == "// }" || trimmed == "//}") {
                    val indent = line.takeWhile { it.isWhitespace() }
                    lines[i] = "$indent}"
                    modified = true
                    bracesToRestore--
                }

                i--
            }

            // If we still need closing braces and didn't find commented ones, add them
            if (bracesToRestore > 0) {
                // Find last non-empty line
                var lastNonEmptyIndex = lines.size - 1
                while (lastNonEmptyIndex >= 0 && lines[lastNonEmptyIndex].trim().isEmpty()) {
                    lastNonEmptyIndex--
                }

                // Add missing closing braces
                for (j in 0 until bracesToRestore) {
                    lines.add(lastNonEmptyIndex + 1 + j, "}")
                    modified = true
                }
            }
        }

        return if (modified) lines.joinToString("\n") else content
    }

    /**
     * Fix remaining orphaned code not caught by previous fixes.
     * Look for common patterns of code at class level that should be in methods.
     */
    private fun fixRemainingOrphanedCode(content: String): String {
        val lines = content.lines().toMutableList()
        var modified = false
        var i = 0

        // Track context
        var classDepth = 0
        var inMethod = false
        var methodBraceDepth = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // Skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("/*") || trimmed.startsWith("*")) {
                i++
                continue
            }

            // Track class entry
            if (trimmed.matches(Regex("""^(public\s+|private\s+|protected\s+)?(static\s+)?(final\s+)?(abstract\s+)?(class|interface|enum)\s+.*"""))) {
                classDepth++
            }

            // Track method entry (not field declarations)
            if (classDepth > 0 && !inMethod) {
                val methodPattern = Regex("""^(public|private|protected|static|final|synchronized|native|abstract|\s)+[\w<>\[\],\s]+\s+\w+\s*\([^)]*\)\s*(throws\s+[\w,\s]+)?\s*\{""")
                if (trimmed.matches(methodPattern)) {
                    inMethod = true
                    methodBraceDepth = 0
                    for (c in trimmed) {
                        when (c) {
                            '{' -> methodBraceDepth++
                            '}' -> methodBraceDepth--
                        }
                    }
                }
            }

            // Track braces in method
            if (inMethod) {
                for (c in trimmed) {
                    when (c) {
                        '{' -> methodBraceDepth++
                        '}' -> methodBraceDepth--
                    }
                }
                if (methodBraceDepth <= 0) {
                    inMethod = false
                }
            }

            // Detect orphaned code at class level
            if (classDepth > 0 && !inMethod) {
                val isOrphanedStatement =
                    // While, if, for, switch statements
                    trimmed.matches(Regex("""^(while|if|for|switch)\s*\(.*""")) ||
                    // Try-catch blocks
                    trimmed.matches(Regex("""^try\s*\{.*""")) ||
                    // Field assignment without declaration (e.g., fieldName = value;)
                    (trimmed.matches(Regex("""^[a-z_]\w*\s*=\s*.+;$""")) &&
                     !trimmed.contains("static") && !trimmed.contains("final") &&
                     !trimmed.matches(Regex("""^[A-Z].*"""))) ||  // Skip class names
                    // Method calls at class level (e.g., someMethod();)
                    trimmed.matches(Regex("""^[a-z_]\w*\s*\([^)]*\)\s*;$""")) ||
                    // Array assignments (e.g., arr[0] = value;)
                    trimmed.matches(Regex("""^\w+\s*\[\s*\d+\s*\]\s*=.+;$"""))

                if (isOrphanedStatement) {
                    val indent = line.takeWhile { it.isWhitespace() }
                    lines[i] = "$indent// ORPHANED_CODE: $trimmed"
                    modified = true
                }
            }

            // Track class exit
            if (trimmed == "}" && classDepth > 0 && !inMethod) {
                classDepth--
            }

            i++
        }

        return if (modified) lines.joinToString("\n") else content
    }
}
