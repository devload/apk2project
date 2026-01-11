package com.whatap.apk2project.deobfuscator.ast

import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.whatap.apk2project.deobfuscator.models.*
import com.whatap.apk2project.utils.Logger
import java.io.File

/**
 * Parses Java source files using JavaParser to extract AST information
 */
class JavaAstParser {

    companion object {
        private val OBFUSCATED_NAME_PATTERNS = listOf(
            Regex("^[a-z]$"),                    // Single letter: a, b, c
            Regex("^[a-z]{2}$"),                 // Two letters: aa, ab
            Regex("^[a-z][0-9]+$"),              // Letter + numbers: a0, b1
            Regex("^[a-z]{1,3}[A-Z]?[0-9]*$"),   // ProGuard pattern
            Regex("^[a-z]_[a-z]+$"),             // R8 underscore: a_b
            Regex("^[A-Z][0-9]{2,}[a-z]*$"),     // Class number: C0001
            Regex("^access\\$\\d+$"),            // Kotlin accessor
            Regex("^lambda\\$.*$"),              // Lambda
            Regex("^.*\\\$DefaultImpls$")         // Kotlin default impl
        )

        private val SYNTHETIC_METHOD_PATTERNS = listOf(
            Regex("^access\\$.*$"),
            Regex("^lambda\\$.*$"),
            Regex("^.*\\$.*\\$.*$")
        )
    }

    /**
     * Parse all Java files in a directory
     */
    fun parseDirectory(sourceDir: File): List<ParsedClass> {
        val parsedClasses = mutableListOf<ParsedClass>()

        sourceDir.walkTopDown()
            .filter { it.extension == "java" }
            .forEach { file ->
                try {
                    parseFile(file)?.let { parsedClasses.add(it) }
                } catch (e: Exception) {
                    Logger.debug("Failed to parse ${file.name}: ${e.message}")
                }
            }

        return parsedClasses
    }

    /**
     * Parse a single Java file
     */
    fun parseFile(file: File): ParsedClass? {
        return try {
            val cu: CompilationUnit = StaticJavaParser.parse(file)
            extractClassInfo(cu, file)
        } catch (e: Exception) {
            Logger.debug("Parse error in ${file.name}: ${e.message}")
            null
        }
    }

    private fun extractClassInfo(cu: CompilationUnit, file: File): ParsedClass? {
        val packageName = cu.packageDeclaration
            .map { it.nameAsString }
            .orElse("")

        val classDecl = cu.findFirst(ClassOrInterfaceDeclaration::class.java)
            .orElse(null) ?: return null

        val className = classDecl.nameAsString
        val fqcn = if (packageName.isNotEmpty()) "$packageName.$className" else className

        val methods = classDecl.methods.mapNotNull { extractMethodInfo(it, fqcn) }
        val fields = classDecl.fields.flatMap { extractFieldInfo(it) }

        return ParsedClass(
            packageName = packageName,
            className = className,
            fullyQualifiedName = fqcn,
            superClass = classDecl.extendedTypes.firstOrNull()?.nameAsString,
            interfaces = classDecl.implementedTypes.map { it.nameAsString },
            methods = methods,
            fields = fields,
            sourceFile = file,
            isObfuscated = isObfuscatedName(className)
        )
    }

    private fun extractMethodInfo(method: MethodDeclaration, ownerFqcn: String): ParsedMethod? {
        val methodName = method.nameAsString

        val methodCalls = mutableListOf<MethodCallInfo>()
        method.findAll(MethodCallExpr::class.java).forEach { call ->
            methodCalls.add(
                MethodCallInfo(
                    targetClass = extractTargetClass(call),
                    methodName = call.nameAsString,
                    argumentTypes = call.arguments.map { inferType(it.toString()) },
                    lineNumber = call.begin.map { it.line }.orElse(0),
                    isStatic = call.scope.map { it.toString() }.orElse("").let {
                        it.isNotEmpty() && it[0].isUpperCase()
                    }
                )
            )
        }

        val fieldAccesses = mutableListOf<String>()
        method.findAll(FieldAccessExpr::class.java).forEach { access ->
            fieldAccesses.add(access.nameAsString)
        }
        method.findAll(NameExpr::class.java).forEach { name ->
            if (name.nameAsString.matches(Regex("^[a-z].*"))) {
                fieldAccesses.add(name.nameAsString)
            }
        }

        val stringLiterals = mutableListOf<String>()
        method.findAll(StringLiteralExpr::class.java).forEach { str ->
            stringLiterals.add(str.value)
        }

        val annotations = method.annotations.map { it.nameAsString }

        val signature = buildSignature(method)
        val bodySource = method.body.map { it.toString() }.orElse("")

        val isSynthetic = SYNTHETIC_METHOD_PATTERNS.any { it.matches(methodName) } ||
                method.annotations.any { it.nameAsString == "Synthetic" }

        return ParsedMethod(
            name = methodName,
            signature = signature,
            returnType = method.typeAsString,
            parameters = method.parameters.map {
                ParsedParameter(it.nameAsString, it.typeAsString)
            },
            methodCalls = methodCalls,
            fieldAccesses = fieldAccesses.distinct(),
            stringLiterals = stringLiterals,
            annotations = annotations,
            lineStart = method.begin.map { it.line }.orElse(0),
            lineEnd = method.end.map { it.line }.orElse(0),
            bodySource = bodySource,
            isStatic = method.isStatic,
            isSynthetic = isSynthetic,
            isObfuscated = isObfuscatedName(methodName)
        )
    }

    private fun extractFieldInfo(field: FieldDeclaration): List<ParsedField> {
        return field.variables.map { variable ->
            ParsedField(
                name = variable.nameAsString,
                type = variable.typeAsString,
                modifiers = field.modifiers.map { it.keyword.asString() },
                isObfuscated = isObfuscatedName(variable.nameAsString)
            )
        }
    }

    private fun extractTargetClass(call: MethodCallExpr): String? {
        return call.scope.map { scope ->
            when {
                scope.toString() == "this" -> null
                scope.toString() == "super" -> "super"
                scope is NameExpr -> scope.nameAsString
                scope is MethodCallExpr -> inferType(scope.toString())
                else -> scope.toString()
            }
        }.orElse(null)
    }

    private fun buildSignature(method: MethodDeclaration): String {
        val params = method.parameters.joinToString(", ") { it.typeAsString }
        return "${method.nameAsString}($params)"
    }

    private fun inferType(expr: String): String {
        return when {
            expr.startsWith("\"") -> "String"
            expr.matches(Regex("\\d+L?")) -> "int"
            expr.matches(Regex("\\d+\\.\\d+[fFdD]?")) -> "double"
            expr == "true" || expr == "false" -> "boolean"
            expr == "null" -> "Object"
            else -> "Object"
        }
    }

    /**
     * Check if a name appears to be obfuscated
     */
    fun isObfuscatedName(name: String): Boolean {
        if (name.isEmpty()) return false

        // Skip known non-obfuscated patterns
        if (name in listOf("toString", "hashCode", "equals", "clone", "finalize")) return false
        if (name.startsWith("get") || name.startsWith("set") || name.startsWith("is")) return false
        if (name.startsWith("on") && name.length > 2 && name[2].isUpperCase()) return false

        return OBFUSCATED_NAME_PATTERNS.any { it.matches(name) }
    }
}
