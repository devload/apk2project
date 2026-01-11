package com.whatap.apk2project.deobfuscator.renamer

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.comments.LineComment
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.visitor.ModifierVisitor
import com.github.javaparser.ast.visitor.Visitable
import com.whatap.apk2project.deobfuscator.client.MethodAnalysisResult
import com.whatap.apk2project.deobfuscator.model.MethodNode
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 소스 코드의 이름을 변경하고 주석을 추가하는 리네이머
 */
class SourceRenamer {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val parser = JavaParser()

    // 리네임 히스토리
    private val renameHistory = mutableListOf<RenameEntry>()

    /**
     * 메소드 리네이밍 적용
     */
    fun renameMethod(
        file: File,
        methodNode: MethodNode,
        analysis: MethodAnalysisResult
    ): RenameResult {
        val originalContent = file.readText()

        return try {
            val parseResult = parser.parse(file)
            if (!parseResult.isSuccessful) {
                return RenameResult.Failure("Parse failed: ${parseResult.problems}")
            }

            val cu = parseResult.result.orElseThrow()
            var renamed = false

            cu.accept(object : ModifierVisitor<Void>() {
                override fun visit(n: MethodDeclaration, arg: Void?): Visitable {
                    val signature = "(${n.parameters.joinToString(",") { it.typeAsString }})"

                    if (n.nameAsString == methodNode.methodName && signature == methodNode.signature) {
                        // 주석 추가
                        val comment = buildMethodComment(
                            originalName = methodNode.methodName,
                            newName = analysis.suggestedName,
                            description = analysis.description,
                            returnDescription = analysis.returnDescription,
                            parameters = analysis.parameters.map { "${it.name}: ${it.description}" }
                        )
                        n.setComment(LineComment(comment))

                        // 메소드 이름 변경
                        n.setName(analysis.suggestedName)

                        // 로컬 변수 리네이밍
                        if (analysis.localVariables.isNotEmpty()) {
                            renameLocalVariables(n, analysis.localVariables)
                        }

                        renamed = true

                        logger.info("Renamed method: ${methodNode.methodName} -> ${analysis.suggestedName}")
                        if (analysis.localVariables.isNotEmpty()) {
                            logger.info("  Renamed ${analysis.localVariables.size} local variables")
                        }
                    }
                    return super.visit(n, arg)
                }
            }, null)

            if (renamed) {
                // 파일 저장
                file.writeText(cu.toString())

                // 히스토리 기록
                val entry = RenameEntry(
                    timestamp = LocalDateTime.now(),
                    file = file,
                    type = RenameType.METHOD,
                    originalName = methodNode.methodName,
                    newName = analysis.suggestedName,
                    description = analysis.description,
                    className = methodNode.className
                )
                renameHistory.add(entry)

                RenameResult.Success(entry)
            } else {
                RenameResult.Failure("Method not found: ${methodNode.methodName}")
            }
        } catch (e: Exception) {
            // 롤백
            file.writeText(originalContent)
            RenameResult.Failure("Rename failed: ${e.message}")
        }
    }

    /**
     * 클래스 리네이밍 적용
     */
    fun renameClass(
        file: File,
        originalClassName: String,
        newClassName: String,
        description: String,
        packageRename: String? = null
    ): RenameResult {
        val originalContent = file.readText()

        return try {
            val parseResult = parser.parse(file)
            if (!parseResult.isSuccessful) {
                return RenameResult.Failure("Parse failed: ${parseResult.problems}")
            }

            val cu = parseResult.result.orElseThrow()
            var renamed = false

            cu.accept(object : ModifierVisitor<Void>() {
                override fun visit(n: ClassOrInterfaceDeclaration, arg: Void?): Visitable {
                    if (n.nameAsString == originalClassName) {
                        // 클래스 주석 추가
                        val comment = buildClassComment(
                            originalName = originalClassName,
                            newName = newClassName,
                            description = description,
                            originalPackage = cu.packageDeclaration.map { it.nameAsString }.orElse(""),
                            newPackage = packageRename
                        )
                        n.setComment(LineComment(comment))

                        // 이름 변경
                        n.setName(newClassName)
                        renamed = true

                        logger.info("Renamed class: $originalClassName -> $newClassName")
                    }
                    return super.visit(n, arg)
                }
            }, null)

            if (renamed) {
                file.writeText(cu.toString())

                val entry = RenameEntry(
                    timestamp = LocalDateTime.now(),
                    file = file,
                    type = RenameType.CLASS,
                    originalName = originalClassName,
                    newName = newClassName,
                    description = description
                )
                renameHistory.add(entry)

                // 파일명도 변경 (필요시)
                if (file.nameWithoutExtension == originalClassName) {
                    val newFile = File(file.parentFile, "$newClassName.java")
                    file.renameTo(newFile)
                    entry.newFile = newFile
                }

                RenameResult.Success(entry)
            } else {
                RenameResult.Failure("Class not found: $originalClassName")
            }
        } catch (e: Exception) {
            file.writeText(originalContent)
            RenameResult.Failure("Rename failed: ${e.message}")
        }
    }

    /**
     * 필드 리네이밍 적용
     */
    fun renameField(
        file: File,
        className: String,
        originalFieldName: String,
        newFieldName: String,
        description: String
    ): RenameResult {
        val originalContent = file.readText()

        return try {
            val parseResult = parser.parse(file)
            if (!parseResult.isSuccessful) {
                return RenameResult.Failure("Parse failed")
            }

            val cu = parseResult.result.orElseThrow()
            var renamed = false

            cu.accept(object : ModifierVisitor<Void>() {
                override fun visit(n: FieldDeclaration, arg: Void?): Visitable {
                    n.variables.forEach { variable ->
                        if (variable.nameAsString == originalFieldName) {
                            // 주석 추가
                            val comment = "[Deobfuscated] $originalFieldName -> $newFieldName: $description"
                            n.setComment(LineComment(comment))

                            // 이름 변경
                            variable.setName(newFieldName)
                            renamed = true

                            logger.info("Renamed field: $originalFieldName -> $newFieldName")
                        }
                    }
                    return super.visit(n, arg)
                }
            }, null)

            if (renamed) {
                file.writeText(cu.toString())

                val entry = RenameEntry(
                    timestamp = LocalDateTime.now(),
                    file = file,
                    type = RenameType.FIELD,
                    originalName = originalFieldName,
                    newName = newFieldName,
                    description = description,
                    className = className
                )
                renameHistory.add(entry)

                RenameResult.Success(entry)
            } else {
                RenameResult.Failure("Field not found: $originalFieldName")
            }
        } catch (e: Exception) {
            file.writeText(originalContent)
            RenameResult.Failure("Rename failed: ${e.message}")
        }
    }

    /**
     * 패키지 리네이밍 적용 (모든 파일 이동 포함)
     *
     * @param sourceDir 소스 루트 디렉토리
     * @param originalPackage 원본 패키지명 (예: "a.b.c")
     * @param newPackage 새 패키지명 (예: "com.payment.api")
     * @param description 패키지 설명
     * @return 변경된 파일 목록
     */
    fun renamePackage(
        sourceDir: File,
        originalPackage: String,
        newPackage: String,
        description: String
    ): PackageRenameResult {
        if (originalPackage == newPackage) {
            return PackageRenameResult(success = false, error = "Package names are identical")
        }

        val affectedFiles = mutableListOf<File>()
        val errors = mutableListOf<String>()

        try {
            // 1. 해당 패키지의 모든 Java 파일 찾기
            val packagePath = originalPackage.replace('.', File.separatorChar)
            val packageDir = File(sourceDir, packagePath)

            if (!packageDir.exists()) {
                return PackageRenameResult(success = false, error = "Package directory not found: $packageDir")
            }

            val javaFiles = packageDir.listFiles { f -> f.extension == "java" }?.toList() ?: emptyList()

            if (javaFiles.isEmpty()) {
                return PackageRenameResult(success = false, error = "No Java files found in package")
            }

            logger.info("Renaming package $originalPackage -> $newPackage (${javaFiles.size} files)")

            // 2. 새 패키지 디렉토리 생성
            val newPackagePath = newPackage.replace('.', File.separatorChar)
            val newPackageDir = File(sourceDir, newPackagePath)
            newPackageDir.mkdirs()

            // 3. 각 파일의 패키지 선언 변경 및 이동
            javaFiles.forEach { file ->
                try {
                    val originalContent = file.readText()
                    val parseResult = parser.parse(file)

                    if (!parseResult.isSuccessful) {
                        errors.add("Failed to parse ${file.name}")
                        return@forEach
                    }

                    val cu = parseResult.result.orElseThrow()

                    // 패키지 선언 변경
                    cu.packageDeclaration.ifPresent { pkg ->
                        if (pkg.nameAsString == originalPackage) {
                            pkg.setName(newPackage)
                        }
                    }

                    // 파일을 새 위치로 이동
                    val newFile = File(newPackageDir, file.name)
                    newFile.writeText(cu.toString())
                    file.delete()

                    affectedFiles.add(newFile)

                    logger.info("  Moved ${file.name} to $newPackagePath")
                } catch (e: Exception) {
                    errors.add("Failed to process ${file.name}: ${e.message}")
                }
            }

            // 4. 모든 소스 파일에서 import 문 업데이트
            val allJavaFiles = sourceDir.walkTopDown()
                .filter { it.extension == "java" }
                .toList()

            allJavaFiles.forEach { file ->
                try {
                    val content = file.readText()
                    if (!content.contains(originalPackage)) return@forEach

                    val parseResult = parser.parse(file)
                    if (!parseResult.isSuccessful) return@forEach

                    val cu = parseResult.result.orElseThrow()
                    var modified = false

                    // import 문 업데이트
                    cu.imports.forEach { imp ->
                        val importName = imp.nameAsString
                        if (importName.startsWith(originalPackage)) {
                            val newImport = importName.replaceFirst(originalPackage, newPackage)
                            imp.setName(newImport)
                            modified = true
                        }
                    }

                    if (modified) {
                        file.writeText(cu.toString())
                        logger.info("  Updated imports in ${file.name}")
                    }
                } catch (e: Exception) {
                    // 에러는 무시 (일부 파일 실패해도 계속 진행)
                }
            }

            // 5. 히스토리 기록
            val entry = RenameEntry(
                timestamp = LocalDateTime.now(),
                file = packageDir,
                type = RenameType.PACKAGE,
                originalName = originalPackage,
                newName = newPackage,
                description = description
            )
            renameHistory.add(entry)

            // 6. 빈 디렉토리 정리
            try {
                if (packageDir.exists() && packageDir.listFiles()?.isEmpty() == true) {
                    packageDir.delete()
                }
            } catch (e: Exception) {
                // 무시
            }

            return PackageRenameResult(
                success = true,
                affectedFiles = affectedFiles,
                errors = errors.takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            logger.error("Package rename failed: ${e.message}")
            return PackageRenameResult(success = false, error = e.message ?: "Unknown error")
        }
    }

    /**
     * 다른 파일에서 참조 업데이트
     */
    fun updateReferences(
        files: List<File>,
        originalName: String,
        newName: String,
        type: RenameType
    ): ReferenceUpdateResult {
        val updatedFiles = mutableListOf<String>()

        files.forEach { file ->
            try {
                val content = file.readText()
                if (!content.contains(originalName)) return@forEach

                val parseResult = parser.parse(file)
                if (!parseResult.isSuccessful) return@forEach

                val cu = parseResult.result.orElseThrow()
                var modified = false

                when (type) {
                    RenameType.METHOD -> {
                        cu.accept(object : ModifierVisitor<Void>() {
                            override fun visit(n: MethodCallExpr, arg: Void?): Visitable {
                                if (n.nameAsString == originalName) {
                                    n.setName(newName)
                                    modified = true
                                }
                                return super.visit(n, arg)
                            }
                        }, null)
                    }
                    RenameType.CLASS -> {
                        // 타입 참조, import 등 업데이트
                        val newContent = content
                            .replace("import $originalName", "import $newName")
                            .replace("$originalName.", "$newName.")
                            .replace(" $originalName ", " $newName ")
                            .replace("<$originalName>", "<$newName>")
                            .replace("($originalName)", "($newName)")
                        if (newContent != content) {
                            file.writeText(newContent)
                            modified = true
                        }
                    }
                    RenameType.FIELD -> {
                        cu.accept(object : ModifierVisitor<Void>() {
                            override fun visit(n: NameExpr, arg: Void?): Visitable {
                                if (n.nameAsString == originalName) {
                                    n.setName(newName)
                                    modified = true
                                }
                                return super.visit(n, arg)
                            }
                        }, null)
                    }
                    RenameType.PACKAGE -> {
                        // 패키지 리네이밍은 renamePackage() 메소드에서 별도 처리
                        // 여기서는 skip
                    }
                }

                if (modified && type != RenameType.CLASS) {
                    file.writeText(cu.toString())
                }

                if (modified) {
                    updatedFiles.add(file.absolutePath)
                }
            } catch (e: Exception) {
                logger.debug("Failed to update references in ${file.name}: ${e.message}")
            }
        }

        return ReferenceUpdateResult(updatedFiles.size, updatedFiles)
    }

    /**
     * 메소드 주석 생성
     */
    private fun buildMethodComment(
        originalName: String,
        newName: String,
        description: String,
        returnDescription: String?,
        parameters: List<String>
    ): String {
        val sb = StringBuilder()
        sb.append(" [Deobfuscated] $originalName -> $newName")
        sb.append("\n * $description")
        if (parameters.isNotEmpty()) {
            parameters.forEach { param ->
                sb.append("\n * @param $param")
            }
        }
        if (returnDescription != null) {
            sb.append("\n * @return $returnDescription")
        }
        return sb.toString()
    }

    /**
     * 로컬 변수 리네이밍
     */
    private fun renameLocalVariables(
        method: MethodDeclaration,
        variableRenames: Map<String, com.whatap.apk2project.deobfuscator.client.VariableRename>
    ) {
        method.accept(object : ModifierVisitor<Void>() {
            override fun visit(n: NameExpr, arg: Void?): Visitable {
                val rename = variableRenames[n.nameAsString]
                if (rename != null) {
                    n.setName(rename.suggestedName)
                    logger.debug("  Renamed variable: ${rename.originalName} -> ${rename.suggestedName}")
                }
                return super.visit(n, arg)
            }
        }, null)
    }

    /**
     * 클래스 주석 생성
     */
    private fun buildClassComment(
        originalName: String,
        newName: String,
        description: String,
        originalPackage: String,
        newPackage: String?
    ): String {
        val sb = StringBuilder()
        sb.append(" [Deobfuscated] 클래스 $originalName -> $newName")
        if (newPackage != null && newPackage != originalPackage) {
            sb.append("\n * 패키지: $originalPackage -> $newPackage")
        }
        sb.append("\n * $description")
        return sb.toString()
    }

    /**
     * 리네임 히스토리 내보내기
     */
    fun exportHistory(outputFile: File) {
        val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        val content = buildString {
            appendLine("# Deobfuscation Rename History")
            appendLine("# Generated: ${LocalDateTime.now().format(dateFormat)}")
            appendLine("# Total renames: ${renameHistory.size}")
            appendLine()

            renameHistory.groupBy { it.type }.forEach { (type, entries) ->
                appendLine("## $type Renames (${entries.size})")
                appendLine()

                entries.forEach { entry ->
                    appendLine("- [${entry.timestamp.format(dateFormat)}] ${entry.originalName} -> ${entry.newName}")
                    appendLine("  - File: ${entry.file.name}")
                    if (entry.className != null) {
                        appendLine("  - Class: ${entry.className}")
                    }
                    appendLine("  - Description: ${entry.description}")
                    appendLine()
                }
            }
        }

        outputFile.writeText(content)
        logger.info("Exported rename history to ${outputFile.absolutePath}")
    }

    fun getHistory(): List<RenameEntry> = renameHistory.toList()
}

enum class RenameType {
    METHOD, CLASS, FIELD, PACKAGE
}

data class RenameEntry(
    val timestamp: LocalDateTime,
    val file: File,
    val type: RenameType,
    val originalName: String,
    val newName: String,
    val description: String,
    val className: String? = null,
    var newFile: File? = null
)

sealed class RenameResult {
    data class Success(val entry: RenameEntry) : RenameResult()
    data class Failure(val reason: String) : RenameResult()
}

data class ReferenceUpdateResult(
    val count: Int,
    val files: List<String>
)

/**
 * 패키지 리네이밍 결과
 */
data class PackageRenameResult(
    val success: Boolean,
    val affectedFiles: List<File> = emptyList(),
    val errors: List<String>? = null,
    val error: String? = null
)
