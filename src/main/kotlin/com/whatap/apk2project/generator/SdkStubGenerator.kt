package com.whatap.apk2project.generator

import com.whatap.apk2project.utils.Logger
import java.io.File

/**
 * Generates stub classes for SDK packages that are filtered out during source organization.
 * This allows the app code to compile even when SDK library source code is not included.
 */
class SdkStubGenerator {

    data class StubGenerationResult(
        val packagesGenerated: Int,
        val classesGenerated: Int,
        val interfacesGenerated: Int
    )

    // Known SDK package prefixes that need stubs
    private val sdkPackagePrefixes = setOf(
        // Korean SDKs
        "com.ahnlab",
        "com.atoncorp",
        "com.callgate",
        "com.enliple",
        "com.greencross",
        "com.igaworks",
        "com.initech",
        "com.interezen",
        "com.ksmartech",
        "com.lguplus",
        "com.mastercard",
        "com.netfunnel",
        "com.nethru",
        "com.nice",
        "com.nimbusds",
        "com.nshc",
        "com.penta",
        "com.posicube",
        "com.seerooinfo",
        "com.skt",
        "com.skp",
        "com.sktelecom",
        "com.ssenstone",
        "com.tmoney",
        "com.tmx",
        "com.visa",
        "com.wizvera",

        // WhaTap SDK
        "io.whatap",

        // Google services (partial - only specific packages)
        "com.google.android.gms.ads",

        // Samsung SDK
        "com.samsung.android.sdk"
    )

    /**
     * Generate stub packages for SDK imports found in source files
     */
    fun generateStubs(
        sourceDir: File,
        stubOutputDir: File,
        filteredPackages: Set<String> = sdkPackagePrefixes
    ): StubGenerationResult {
        Logger.info("Scanning for SDK imports...")

        // Collect all imports from source files
        val sdkImports = collectSdkImports(sourceDir, filteredPackages)

        if (sdkImports.isEmpty()) {
            Logger.info("No SDK imports found that need stubs")
            return StubGenerationResult(0, 0, 0)
        }

        Logger.info("Found ${sdkImports.size} unique SDK class references")

        // Group by package
        val packageGroups = sdkImports.groupBy { it.packageName }

        var classesGenerated = 0
        var interfacesGenerated = 0

        // Generate stub files for each package
        for ((packageName, imports) in packageGroups) {
            val packageDir = File(stubOutputDir, packageName.replace(".", File.separator))
            packageDir.mkdirs()

            for (import in imports) {
                val generated = generateStubFile(packageDir, import)
                if (import.isInterface) {
                    interfacesGenerated += generated
                } else {
                    classesGenerated += generated
                }
            }
        }

        Logger.success("Generated ${packageGroups.size} stub packages with $classesGenerated classes and $interfacesGenerated interfaces")

        return StubGenerationResult(
            packagesGenerated = packageGroups.size,
            classesGenerated = classesGenerated,
            interfacesGenerated = interfacesGenerated
        )
    }

    /**
     * Represents an SDK class/interface that needs a stub
     */
    data class SdkImport(
        val fullClassName: String,
        val packageName: String,
        val className: String,
        val isInterface: Boolean = false,
        val isEnum: Boolean = false,
        val superClass: String? = null,
        val implementedInterfaces: List<String> = emptyList()
    )

    /**
     * Collect all SDK imports from source files
     */
    private fun collectSdkImports(sourceDir: File, filteredPackages: Set<String>): Set<SdkImport> {
        val imports = mutableSetOf<SdkImport>()
        val importPattern = Regex("""import\s+(static\s+)?([a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z_][a-zA-Z0-9_]*)*)\s*;""")
        val wildcardPattern = Regex("""import\s+([a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z_][a-zA-Z0-9_]*)*)\.\*\s*;""")

        sourceDir.walkTopDown()
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .forEach { file ->
                try {
                    val content = file.readText()

                    // Find regular imports
                    importPattern.findAll(content).forEach { match ->
                        val fullName = match.groupValues[2]
                        if (isSdkPackage(fullName, filteredPackages)) {
                            val lastDot = fullName.lastIndexOf('.')
                            if (lastDot > 0) {
                                val packageName = fullName.substring(0, lastDot)
                                val className = fullName.substring(lastDot + 1)

                                // Detect if it's likely an interface based on naming conventions
                                val isInterface = className.startsWith("I") && className.length > 1 &&
                                                  className[1].isUpperCase() ||
                                                  className.endsWith("Listener") ||
                                                  className.endsWith("Callback") ||
                                                  className.endsWith("Handler") ||
                                                  className.endsWith("Observer")

                                imports.add(SdkImport(
                                    fullClassName = fullName,
                                    packageName = packageName,
                                    className = className,
                                    isInterface = isInterface
                                ))
                            }
                        }
                    }

                    // Find wildcard imports and generate common classes
                    wildcardPattern.findAll(content).forEach { match ->
                        val packageName = match.groupValues[1]
                        if (isSdkPackage(packageName, filteredPackages)) {
                            // Add a placeholder class for wildcard imports
                            imports.add(SdkImport(
                                fullClassName = "$packageName.PackageInfo",
                                packageName = packageName,
                                className = "PackageInfo",
                                isInterface = false
                            ))
                        }
                    }

                    // Also scan for fully qualified class references in code
                    findFullyQualifiedReferences(content, filteredPackages).forEach { ref ->
                        imports.add(ref)
                    }

                } catch (e: Exception) {
                    // Skip files that can't be read
                }
            }

        return imports
    }

    /**
     * Find fully qualified class references in code (e.g., com.example.Class.method())
     */
    private fun findFullyQualifiedReferences(content: String, filteredPackages: Set<String>): Set<SdkImport> {
        val refs = mutableSetOf<SdkImport>()

        // Pattern for fully qualified class usage
        val fqPattern = Regex("""([a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+)\.([A-Z][a-zA-Z0-9_]*)""")

        fqPattern.findAll(content).forEach { match ->
            val packageName = match.groupValues[1]
            val className = match.groupValues[2]

            if (isSdkPackage(packageName, filteredPackages)) {
                refs.add(SdkImport(
                    fullClassName = "$packageName.$className",
                    packageName = packageName,
                    className = className,
                    isInterface = false
                ))
            }
        }

        return refs
    }

    /**
     * Check if a package/class belongs to filtered SDK packages
     */
    private fun isSdkPackage(name: String, filteredPackages: Set<String>): Boolean {
        return filteredPackages.any { prefix ->
            name == prefix || name.startsWith("$prefix.")
        }
    }

    /**
     * Generate a stub file for an SDK class/interface
     */
    private fun generateStubFile(packageDir: File, import: SdkImport): Int {
        val stubFile = File(packageDir, "${import.className}.java")

        // Don't overwrite existing files
        if (stubFile.exists()) {
            return 0
        }

        val content = if (import.isInterface) {
            generateInterfaceStub(import)
        } else if (import.isEnum) {
            generateEnumStub(import)
        } else {
            generateClassStub(import)
        }

        stubFile.writeText(content)
        return 1
    }

    /**
     * Generate a stub class
     */
    private fun generateClassStub(import: SdkImport): String {
        return buildString {
            appendLine("package ${import.packageName};")
            appendLine()
            appendLine("/**")
            appendLine(" * Auto-generated stub class for SDK: ${import.fullClassName}")
            appendLine(" * This stub allows compilation when the actual SDK is not included.")
            appendLine(" */")
            appendLine("@SuppressWarnings(\"unused\")")
            appendLine("public class ${import.className} {")
            appendLine()
            appendLine("    /**")
            appendLine("     * Default constructor stub")
            appendLine("     */")
            appendLine("    public ${import.className}() {")
            appendLine("        // Stub constructor")
            appendLine("    }")
            appendLine()
            appendLine("    /**")
            appendLine("     * Constructor with context (common Android pattern)")
            appendLine("     */")
            appendLine("    public ${import.className}(android.content.Context context) {")
            appendLine("        // Stub constructor")
            appendLine("    }")
            appendLine()
            appendLine("    /**")
            appendLine("     * Generic method stub - returns null for objects")
            appendLine("     */")
            appendLine("    public Object invoke(Object... args) {")
            appendLine("        return null;")
            appendLine("    }")
            appendLine()
            appendLine("    /**")
            appendLine("     * Generic method stub - returns empty string")
            appendLine("     */")
            appendLine("    public String getString() {")
            appendLine("        return \"\";")
            appendLine("    }")
            appendLine()
            appendLine("    /**")
            appendLine("     * Generic method stub - returns 0")
            appendLine("     */")
            appendLine("    public int getInt() {")
            appendLine("        return 0;")
            appendLine("    }")
            appendLine()
            appendLine("    /**")
            appendLine("     * Generic method stub - returns false")
            appendLine("     */")
            appendLine("    public boolean getBoolean() {")
            appendLine("        return false;")
            appendLine("    }")
            appendLine("}")
            appendLine()
        }
    }

    /**
     * Generate a stub interface
     */
    private fun generateInterfaceStub(import: SdkImport): String {
        return buildString {
            appendLine("package ${import.packageName};")
            appendLine()
            appendLine("/**")
            appendLine(" * Auto-generated stub interface for SDK: ${import.fullClassName}")
            appendLine(" * This stub allows compilation when the actual SDK is not included.")
            appendLine(" */")
            appendLine("@SuppressWarnings(\"unused\")")
            appendLine("public interface ${import.className} {")
            appendLine()

            // Generate common callback methods based on interface name patterns
            when {
                import.className.endsWith("Listener") -> {
                    appendLine("    /**")
                    appendLine("     * Callback method stub")
                    appendLine("     */")
                    appendLine("    void onEvent(Object... args);")
                    appendLine()
                    appendLine("    /**")
                    appendLine("     * Success callback stub")
                    appendLine("     */")
                    appendLine("    default void onSuccess(Object result) {}")
                    appendLine()
                    appendLine("    /**")
                    appendLine("     * Error callback stub")
                    appendLine("     */")
                    appendLine("    default void onError(Exception error) {}")
                }
                import.className.endsWith("Callback") -> {
                    appendLine("    /**")
                    appendLine("     * Callback method stub")
                    appendLine("     */")
                    appendLine("    void onCallback(Object... args);")
                    appendLine()
                    appendLine("    /**")
                    appendLine("     * Success callback stub")
                    appendLine("     */")
                    appendLine("    default void onSuccess(Object result) {}")
                    appendLine()
                    appendLine("    /**")
                    appendLine("     * Failure callback stub")
                    appendLine("     */")
                    appendLine("    default void onFailure(Exception error) {}")
                }
                import.className.endsWith("Handler") -> {
                    appendLine("    /**")
                    appendLine("     * Handle method stub")
                    appendLine("     */")
                    appendLine("    void handle(Object... args);")
                }
                import.className.endsWith("Observer") -> {
                    appendLine("    /**")
                    appendLine("     * Update method stub")
                    appendLine("     */")
                    appendLine("    void onUpdate(Object... args);")
                }
                else -> {
                    appendLine("    /**")
                    appendLine("     * Generic interface method stub")
                    appendLine("     */")
                    appendLine("    default void execute(Object... args) {}")
                }
            }

            appendLine("}")
            appendLine()
        }
    }

    /**
     * Generate a stub enum
     */
    private fun generateEnumStub(import: SdkImport): String {
        return buildString {
            appendLine("package ${import.packageName};")
            appendLine()
            appendLine("/**")
            appendLine(" * Auto-generated stub enum for SDK: ${import.fullClassName}")
            appendLine(" * This stub allows compilation when the actual SDK is not included.")
            appendLine(" */")
            appendLine("@SuppressWarnings(\"unused\")")
            appendLine("public enum ${import.className} {")
            appendLine("    UNKNOWN;")
            appendLine()
            appendLine("    /**")
            appendLine("     * Generic value getter stub")
            appendLine("     */")
            appendLine("    public int getValue() {")
            appendLine("        return 0;")
            appendLine("    }")
            appendLine("}")
            appendLine()
        }
    }

    /**
     * Analyze source code to detect actual method signatures needed
     * This is a more sophisticated version that can generate more accurate stubs
     */
    fun analyzeAndGenerateAccurateStubs(
        sourceDir: File,
        stubOutputDir: File,
        filteredPackages: Set<String> = sdkPackagePrefixes
    ): StubGenerationResult {
        Logger.info("Performing detailed SDK usage analysis...")

        // Phase 1: Collect all SDK references with usage context
        val usageAnalysis = analyzeUsagePatterns(sourceDir, filteredPackages)

        // Phase 2: Generate stubs based on actual usage
        var classesGenerated = 0
        var interfacesGenerated = 0

        for ((packageName, classUsages) in usageAnalysis) {
            val packageDir = File(stubOutputDir, packageName.replace(".", File.separator))
            packageDir.mkdirs()

            for ((className, usage) in classUsages) {
                val stubFile = File(packageDir, "$className.java")
                if (!stubFile.exists()) {
                    val content = generateAccurateStub(packageName, className, usage)
                    stubFile.writeText(content)

                    if (usage.isInterface) {
                        interfacesGenerated++
                    } else {
                        classesGenerated++
                    }
                }
            }
        }

        val packagesGenerated = usageAnalysis.size
        Logger.success("Generated $packagesGenerated packages with $classesGenerated classes and $interfacesGenerated interfaces")

        return StubGenerationResult(packagesGenerated, classesGenerated, interfacesGenerated)
    }

    /**
     * Usage analysis result for a class
     */
    data class ClassUsage(
        val className: String,
        val isInterface: Boolean = false,
        val isEnum: Boolean = false,
        val constructorArgs: Set<List<String>> = emptySet(),
        val methodCalls: Set<MethodCall> = emptySet(),
        val staticMethodCalls: Set<MethodCall> = emptySet(),
        val fieldAccesses: Set<String> = emptySet(),
        val staticFieldAccesses: Set<String> = emptySet(),
        val implementedBy: Set<String> = emptySet()
    )

    data class MethodCall(
        val name: String,
        val argCount: Int = 0,
        val returnType: String = "Object"
    )

    /**
     * Analyze usage patterns in source code
     */
    private fun analyzeUsagePatterns(
        sourceDir: File,
        filteredPackages: Set<String>
    ): Map<String, Map<String, ClassUsage>> {
        val usageMap = mutableMapOf<String, MutableMap<String, ClassUsage>>()

        // Patterns for detecting usage
        val newInstancePattern = Regex("""new\s+([a-zA-Z_][a-zA-Z0-9_.]*)\s*\(([^)]*)\)""")
        val methodCallPattern = Regex("""([a-zA-Z_][a-zA-Z0-9_]*)\s*\.\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\(""")
        val staticCallPattern = Regex("""([A-Z][a-zA-Z0-9_]*)\s*\.\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*\(""")
        val implementsPattern = Regex("""implements\s+([^{]+)""")
        val extendsPattern = Regex("""extends\s+([a-zA-Z_][a-zA-Z0-9_.]*)\s""")

        sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .forEach { file ->
                try {
                    val content = file.readText()

                    // Collect imports for this file
                    val imports = mutableMapOf<String, String>() // simple name -> full name
                    val importPattern = Regex("""import\s+([a-zA-Z_][a-zA-Z0-9_.]*)\s*;""")
                    importPattern.findAll(content).forEach { match ->
                        val fullName = match.groupValues[1]
                        val simpleName = fullName.substringAfterLast('.')
                        imports[simpleName] = fullName
                    }

                    // Find new instances
                    newInstancePattern.findAll(content).forEach { match ->
                        val className = match.groupValues[1]
                        val fullName = resolveClassName(className, imports, filteredPackages)
                        if (fullName != null) {
                            val packageName = fullName.substringBeforeLast('.')
                            val simpleClassName = fullName.substringAfterLast('.')

                            val packageMap = usageMap.getOrPut(packageName) { mutableMapOf() }
                            val existing = packageMap[simpleClassName] ?: ClassUsage(simpleClassName)

                            val args = match.groupValues[2].split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            val argTypes = args.map { guessType(it) }

                            packageMap[simpleClassName] = existing.copy(
                                constructorArgs = existing.constructorArgs + setOf(argTypes)
                            )
                        }
                    }

                    // Find implements clauses
                    implementsPattern.findAll(content).forEach { match ->
                        val interfaceList = match.groupValues[1]
                        interfaceList.split(",").map { it.trim() }.forEach { interfaceName ->
                            val fullName = resolveClassName(interfaceName, imports, filteredPackages)
                            if (fullName != null) {
                                val packageName = fullName.substringBeforeLast('.')
                                val simpleClassName = fullName.substringAfterLast('.')

                                val packageMap = usageMap.getOrPut(packageName) { mutableMapOf() }
                                val existing = packageMap[simpleClassName] ?: ClassUsage(simpleClassName)
                                packageMap[simpleClassName] = existing.copy(isInterface = true)
                            }
                        }
                    }

                } catch (e: Exception) {
                    // Skip files that can't be processed
                }
            }

        return usageMap
    }

    /**
     * Resolve a class name to its full package name
     */
    private fun resolveClassName(
        className: String,
        imports: Map<String, String>,
        filteredPackages: Set<String>
    ): String? {
        // If already fully qualified
        if (className.contains('.')) {
            return if (isSdkPackage(className, filteredPackages)) className else null
        }

        // Check imports
        val fullName = imports[className]
        if (fullName != null && isSdkPackage(fullName, filteredPackages)) {
            return fullName
        }

        return null
    }

    /**
     * Guess the type of an expression
     */
    private fun guessType(expr: String): String {
        return when {
            expr.matches(Regex("""\d+""")) -> "int"
            expr.matches(Regex("""\d+L""")) -> "long"
            expr.matches(Regex("""\d+\.\d+""")) -> "double"
            expr.matches(Regex("""\d+\.\d+f""")) -> "float"
            expr == "true" || expr == "false" -> "boolean"
            expr.startsWith("\"") -> "String"
            expr == "null" -> "Object"
            expr.startsWith("new ") -> expr.substringAfter("new ").substringBefore("(").trim()
            else -> "Object"
        }
    }

    /**
     * Generate accurate stub based on usage analysis
     */
    private fun generateAccurateStub(
        packageName: String,
        className: String,
        usage: ClassUsage
    ): String {
        return buildString {
            appendLine("package $packageName;")
            appendLine()
            appendLine("/**")
            appendLine(" * Auto-generated SDK stub for: $packageName.$className")
            appendLine(" * Generated based on actual usage patterns in app code.")
            appendLine(" */")
            appendLine("@SuppressWarnings(\"unused\")")

            if (usage.isInterface) {
                appendLine("public interface $className {")
                appendLine()

                // Generate methods from method calls
                for (method in usage.methodCalls) {
                    appendLine("    default ${method.returnType} ${method.name}(${generateParams(method.argCount)}) {")
                    appendLine("        return ${defaultReturn(method.returnType)};")
                    appendLine("    }")
                    appendLine()
                }

                if (usage.methodCalls.isEmpty()) {
                    appendLine("    // Interface stub - implement as needed")
                    appendLine("    default void onEvent(Object... args) {}")
                }
            } else if (usage.isEnum) {
                appendLine("public enum $className {")
                appendLine("    DEFAULT;")
            } else {
                appendLine("public class $className {")
                appendLine()

                // Generate constructors
                if (usage.constructorArgs.isEmpty()) {
                    appendLine("    public $className() {}")
                    appendLine()
                    appendLine("    public $className(android.content.Context context) {}")
                } else {
                    for (argTypes in usage.constructorArgs) {
                        val params = argTypes.mapIndexed { i, type -> "$type arg$i" }.joinToString(", ")
                        appendLine("    public $className($params) {}")
                    }
                }
                appendLine()

                // Generate methods from method calls
                for (method in usage.methodCalls) {
                    appendLine("    public ${method.returnType} ${method.name}(${generateParams(method.argCount)}) {")
                    appendLine("        return ${defaultReturn(method.returnType)};")
                    appendLine("    }")
                    appendLine()
                }

                // Generate static methods
                for (method in usage.staticMethodCalls) {
                    appendLine("    public static ${method.returnType} ${method.name}(${generateParams(method.argCount)}) {")
                    appendLine("        return ${defaultReturn(method.returnType)};")
                    appendLine("    }")
                    appendLine()
                }

                // Generate field accesses
                for (field in usage.fieldAccesses) {
                    appendLine("    public Object $field;")
                }
                for (field in usage.staticFieldAccesses) {
                    appendLine("    public static Object $field;")
                }
            }

            appendLine("}")
        }
    }

    private fun generateParams(count: Int): String {
        return (0 until count).joinToString(", ") { "Object arg$it" }
    }

    private fun defaultReturn(type: String): String {
        return when (type) {
            "void" -> ""
            "int", "long", "short", "byte" -> "0"
            "float", "double" -> "0.0"
            "boolean" -> "false"
            "char" -> "'\\0'"
            "String" -> "\"\""
            else -> "null"
        }
    }
}
