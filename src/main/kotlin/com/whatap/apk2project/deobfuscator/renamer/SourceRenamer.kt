package com.whatap.apk2project.deobfuscator.renamer

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.VariableDeclarator
import com.github.javaparser.ast.comments.LineComment
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.stmt.CatchClause
import com.github.javaparser.ast.stmt.ForEachStmt
import com.github.javaparser.ast.visitor.ModifierVisitor
import com.github.javaparser.ast.visitor.Visitable
import com.whatap.apk2project.deobfuscator.client.MethodAnalysisResult
import com.whatap.apk2project.deobfuscator.model.MethodNode
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * 소스 코드의 이름을 변경하고 주석을 추가하는 리네이머
 *
 * 파일 단위 락을 사용하여 동시성 문제 방지:
 * 같은 파일에 있는 메서드들은 순차적으로 리네이밍됨
 */
class SourceRenamer {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val parser = JavaParser()

    // 파일 단위 락 (경로 -> ReentrantLock)
    private val fileLocks = ConcurrentHashMap<String, ReentrantLock>()

    // 리네임 히스토리
    private val renameHistory = mutableListOf<RenameEntry>()

    // 파일별 기존 이름 캐시 (충돌 감지용)
    private val existingNamesCache = ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * 파일에서 기존 메소드 이름들을 추출 (충돌 감지용)
     */
    private fun getExistingMethodNames(file: File): Set<String> {
        return existingNamesCache.getOrPut(file.absolutePath) {
            val names = mutableSetOf<String>()
            try {
                val parseResult = parser.parse(file)
                if (parseResult.isSuccessful) {
                    parseResult.result.orElseThrow().findAll(MethodDeclaration::class.java).forEach { method ->
                        names.add(method.nameAsString)
                    }
                }
            } catch (e: Exception) {
                logger.debug("Failed to extract method names from ${file.name}: ${e.message}")
            }
            names
        }
    }

    /**
     * 리네이밍 충돌 검사
     *
     * @return null이면 충돌 없음, 아니면 충돌을 피할 수 있는 대안 이름 제안
     */
    fun checkRenameConflict(
        file: File,
        originalName: String,
        newName: String,
        signature: String
    ): RenameConflictResult {
        val existingNames = getExistingMethodNames(file)

        // 원본 이름 제외하고 동일한 이름이 있는지 확인
        val conflictExists = (existingNames - originalName).any { existing ->
            existing == newName
        }

        return if (conflictExists) {
            // 충돌이 있으면 대안 이름 생성
            var alternative = newName
            var suffix = 2
            while ((existingNames - originalName).contains(alternative)) {
                alternative = "${newName}$suffix"
                suffix++
                if (suffix > 10) {
                    // 10번 시도 후 포기
                    return RenameConflictResult(
                        hasConflict = true,
                        conflictingName = newName,
                        suggestedAlternative = null,
                        reason = "Could not find non-conflicting name after 10 attempts"
                    )
                }
            }
            RenameConflictResult(
                hasConflict = true,
                conflictingName = newName,
                suggestedAlternative = alternative,
                reason = "Method name '$newName' already exists in class"
            )
        } else {
            RenameConflictResult(hasConflict = false)
        }
    }

    /**
     * 캐시 무효화 (파일 변경 후 호출)
     */
    fun invalidateCache(file: File) {
        existingNamesCache.remove(file.absolutePath)
    }

    /**
     * 메소드 리네이밍 적용
     *
     * 파일 단위 락을 사용하여 동시성 문제 방지:
     * 같은 파일에 있는 여러 메서드가 동시에 리네이밍되는 것을 방지
     */
    fun renameMethod(
        file: File,
        methodNode: MethodNode,
        analysis: MethodAnalysisResult
    ): RenameResult {
        // 파일별 Lock 가져오기 (없으면 생성)
        val lock = fileLocks.computeIfAbsent(file.absolutePath) { ReentrantLock() }

        // 파일 락 획득
        lock.lock()
        try {
            return renameMethodImpl(file, methodNode, analysis)
        } finally {
            lock.unlock()
        }
    }

    /**
     * 메소드 리네이밍 실제 구현
     */
    private fun renameMethodImpl(
        file: File,
        methodNode: MethodNode,
        analysis: MethodAnalysisResult
    ): RenameResult {
        // 충돌 검사
        val conflictResult = checkRenameConflict(
            file = file,
            originalName = methodNode.methodName,
            newName = analysis.suggestedName,
            signature = methodNode.signature
        )

        val finalName = if (conflictResult.hasConflict) {
            if (conflictResult.suggestedAlternative != null) {
                logger.warn("Rename conflict detected: ${analysis.suggestedName} already exists. Using alternative: ${conflictResult.suggestedAlternative}")
                conflictResult.suggestedAlternative
            } else {
                return RenameResult.Failure("Rename conflict: ${conflictResult.reason}")
            }
        } else {
            analysis.suggestedName
        }

        // 최종 이름이 원본과 같으면 스킵
        if (finalName == methodNode.methodName) {
            return RenameResult.Failure("Final name same as original after conflict resolution")
        }

        val originalContent = file.readText()

        return try {
            val parseResult = parser.parse(file)
            if (!parseResult.isSuccessful) {
                return RenameResult.Failure("Parse failed: ${parseResult.problems}")
            }

            val cu = parseResult.result.orElseThrow()
            var renamed = false
            var actualRenamedVars: Map<String, com.whatap.apk2project.deobfuscator.client.VariableRename> = emptyMap()

            cu.accept(object : ModifierVisitor<Void>() {
                override fun visit(n: MethodDeclaration, arg: Void?): Visitable {
                    val signature = "(${n.parameters.joinToString(",") { it.typeAsString }})"

                    if (n.nameAsString == methodNode.methodName && signature == methodNode.signature) {
                        // 주석 추가
                        val comment = buildMethodComment(
                            originalName = methodNode.methodName,
                            newName = finalName,
                            description = analysis.description,
                            returnDescription = analysis.returnDescription,
                            parameters = analysis.parameters.map { "${it.name}: ${it.description}" }
                        )
                        n.setComment(LineComment(comment))

                        // 메소드 이름 변경
                        n.setName(finalName)

                        // 로컬 변수 리네이밍 (실제 적용된 것만 반환)
                        if (analysis.localVariables.isNotEmpty()) {
                            actualRenamedVars = renameLocalVariables(n, analysis.localVariables)
                        }

                        renamed = true

                        logger.info("Renamed method: ${methodNode.methodName} -> $finalName")
                        if (actualRenamedVars.isNotEmpty()) {
                            logger.info("  Renamed ${actualRenamedVars.size} local variables: ${actualRenamedVars.keys.joinToString(", ")}")
                        } else if (analysis.localVariables.isNotEmpty()) {
                            logger.debug("  AI suggested ${analysis.localVariables.size} variable renames but none were valid")
                        }
                    }
                    return super.visit(n, arg)
                }
            }, null)

            if (renamed) {
                // 파일 저장
                logger.info("Saving renamed file: ${file.absolutePath}")

                try {
                    // 큰 파일(1MB 이상)은 AST 대신 직접 문자열 치환 사용
                    val fileSizeMB = file.length() / (1024 * 1024)
                    if (fileSizeMB > 1) {
                        logger.warn("Large file detected (${fileSizeMB}MB), using direct string replacement instead of AST serialization")

                        val startTime = System.currentTimeMillis()

                        // 직접 문자열 치환
                        var content = file.readText()

                        // 메서드 이름 변경
                        content = content.replace(
                            "\\b${Regex.escape(methodNode.methodName)}\\b".toRegex(),
                            finalName
                        )

                        // 로컬 변수 변경
                        actualRenamedVars.forEach { (oldName, newVar) ->
                            content = content.replace(
                                "\\b${Regex.escape(oldName)}\\b".toRegex(),
                                newVar.suggestedName
                            )
                        }

                        file.writeText(content)
                        val elapsed = System.currentTimeMillis() - startTime

                        logger.info("File saved successfully using string replacement: ${file.absolutePath}, took: ${elapsed}ms")
                    } else {
                        // 작은 파일은 AST 사용 (정확도 높음)
                        val content = cu.toString()
                        logger.info("Generated AST content, length: ${content.length} chars")

                        val startTime = System.currentTimeMillis()
                        file.writeText(content)
                        val elapsed = System.currentTimeMillis() - startTime

                        logger.info("File saved successfully: ${file.absolutePath}, size: ${file.length()} bytes, took: ${elapsed}ms")
                    }
                } catch (e: Exception) {
                    logger.error("Failed to save file: ${file.absolutePath}, error: ${e.message}", e)
                    throw e
                }

                // 히스토리 기록
                val entry = RenameEntry(
                    timestamp = LocalDateTime.now(),
                    file = file,
                    type = RenameType.METHOD,
                    originalName = methodNode.methodName,
                    newName = finalName,
                    description = analysis.description,
                    className = methodNode.className
                )
                renameHistory.add(entry)

                // 캐시 무효화 (파일 변경 후)
                invalidateCache(file)

                // 실제 적용된 변수 리네임만 반환
                val actualVarRenamesSimple = actualRenamedVars.mapValues { it.value.suggestedName }
                RenameResult.Success(entry, actualVarRenamesSimple)
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
     *
     * 지원하는 패턴:
     * - 메소드 호출: obj.methodName(), methodName()
     * - 클래스 참조: import, 타입 선언, 제네릭, 배열
     * - 필드 참조: obj.fieldName, fieldName
     * - 리플렉션 패턴: getMethod("name"), getField("name")
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

                var newContent = content
                var modified = false

                when (type) {
                    RenameType.METHOD -> {
                        // 1. 일반 메소드 호출 (AST 기반)
                        val parseResult = parser.parse(file)
                        if (parseResult.isSuccessful) {
                            val cu = parseResult.result.orElseThrow()
                            cu.accept(object : ModifierVisitor<Void>() {
                                override fun visit(n: MethodCallExpr, arg: Void?): Visitable {
                                    if (n.nameAsString == originalName) {
                                        n.setName(newName)
                                        modified = true
                                    }
                                    return super.visit(n, arg)
                                }
                            }, null)
                            if (modified) {
                                newContent = cu.toString()
                            }
                        }

                        // 2. 리플렉션 패턴 (정규식 기반)
                        val reflectionPatterns = listOf(
                            // getMethod("methodName", ...) or getDeclaredMethod("methodName", ...)
                            Regex("""(getMethod|getDeclaredMethod)\s*\(\s*"${Regex.escape(originalName)}""""),
                            // Method.invoke(obj, "methodName")
                            Regex(""""${Regex.escape(originalName)}"\s*\)""")
                        )
                        reflectionPatterns.forEach { pattern ->
                            if (pattern.containsMatchIn(newContent)) {
                                newContent = newContent.replace(
                                    "\"$originalName\"",
                                    "\"$newName\""
                                )
                                modified = true
                            }
                        }
                    }
                    RenameType.CLASS -> {
                        // 1. import 문
                        newContent = newContent.replace(
                            "import $originalName",
                            "import $newName"
                        )
                        newContent = newContent.replace(
                            "import ${originalName};",
                            "import ${newName};"
                        )

                        // 2. 단순 타입 참조 (단어 경계 사용)
                        newContent = newContent.replace(
                            Regex("\\b${Regex.escape(originalName)}\\b(?!\\.)"),
                            newName
                        )

                        // 3. 제네릭 타입: List<OldName>, Map<String, OldName>
                        newContent = newContent.replace(
                            Regex("<\\s*${Regex.escape(originalName)}\\s*>"),
                            "<$newName>"
                        )
                        newContent = newContent.replace(
                            Regex(",\\s*${Regex.escape(originalName)}\\s*>"),
                            ", $newName>"
                        )
                        newContent = newContent.replace(
                            Regex("<\\s*${Regex.escape(originalName)}\\s*,"),
                            "<$newName, "
                        )

                        // 4. 배열 타입: OldName[]
                        newContent = newContent.replace(
                            Regex("\\b${Regex.escape(originalName)}\\s*\\[\\s*\\]"),
                            "$newName[]"
                        )

                        // 5. 인스턴스 생성: new OldName()
                        newContent = newContent.replace(
                            Regex("new\\s+${Regex.escape(originalName)}\\s*\\("),
                            "new $newName("
                        )

                        // 6. 클래스 리터럴: OldName.class
                        newContent = newContent.replace(
                            "${originalName}.class",
                            "${newName}.class"
                        )

                        // 7. 리플렉션: Class.forName("OldName"), loadClass("OldName")
                        newContent = newContent.replace(
                            "\"$originalName\"",
                            "\"$newName\""
                        )

                        // 8. 정적 메소드/필드 참조: OldName.staticMethod()
                        newContent = newContent.replace(
                            "${originalName}.",
                            "${newName}."
                        )

                        modified = newContent != content
                    }
                    RenameType.FIELD -> {
                        // 1. 일반 필드 참조 (AST 기반)
                        val parseResult = parser.parse(file)
                        if (parseResult.isSuccessful) {
                            val cu = parseResult.result.orElseThrow()
                            cu.accept(object : ModifierVisitor<Void>() {
                                override fun visit(n: NameExpr, arg: Void?): Visitable {
                                    if (n.nameAsString == originalName) {
                                        n.setName(newName)
                                        modified = true
                                    }
                                    return super.visit(n, arg)
                                }
                            }, null)
                            if (modified) {
                                newContent = cu.toString()
                            }
                        }

                        // 2. 리플렉션 패턴: getField("fieldName"), getDeclaredField("fieldName")
                        val reflectionPatterns = listOf(
                            Regex("""(getField|getDeclaredField)\s*\(\s*"${Regex.escape(originalName)}"""")
                        )
                        reflectionPatterns.forEach { pattern ->
                            if (pattern.containsMatchIn(newContent)) {
                                newContent = newContent.replace(
                                    "\"$originalName\"",
                                    "\"$newName\""
                                )
                                modified = true
                            }
                        }
                    }
                    RenameType.PACKAGE -> {
                        // 패키지 리네이밍은 renamePackage() 메소드에서 별도 처리
                        // 여기서는 skip
                    }
                }

                if (modified) {
                    file.writeText(newContent)
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
     * 로컬 변수 리네이밍 (선언 + 사용 모두 처리)
     * @return 실제로 적용된 유효한 리네임 맵
     */
    private fun renameLocalVariables(
        method: MethodDeclaration,
        variableRenames: Map<String, com.whatap.apk2project.deobfuscator.client.VariableRename>
    ): Map<String, com.whatap.apk2project.deobfuscator.client.VariableRename> {
        // 먼저 실제로 존재하는 변수 이름 수집
        val existingNames = mutableSetOf<String>()

        // 메소드 파라미터
        method.parameters.forEach { existingNames.add(it.nameAsString) }

        // 로컬 변수 선언
        method.findAll(VariableDeclarator::class.java).forEach { existingNames.add(it.nameAsString) }

        // catch 파라미터
        method.findAll(CatchClause::class.java).forEach { existingNames.add(it.parameter.nameAsString) }

        // for-each 변수 (for (Type var : iterable))
        method.findAll(ForEachStmt::class.java).forEach { foreach ->
            foreach.variable.variables.forEach { existingNames.add(it.nameAsString) }
        }

        // 실제 존재하는 변수만 필터링
        val validRenames = variableRenames.filter { (oldName, _) ->
            val exists = existingNames.contains(oldName)
            if (!exists) {
                logger.debug("  Skipping invalid variable rename: $oldName (not found in method)")
            }
            exists
        }

        if (validRenames.isEmpty()) {
            logger.debug("  No valid variable renames to apply")
            return emptyMap()
        }

        // 0. 메소드 파라미터 리네이밍 먼저 처리 (for (Parameter p : method.getParameters()))
        method.parameters.forEach { param ->
            val rename = validRenames[param.nameAsString]
            if (rename != null && rename.suggestedName.isNotEmpty()) {
                param.setName(rename.suggestedName)
                logger.debug("  Renamed parameter: ${rename.originalName} -> ${rename.suggestedName}")
            }
        }

        method.accept(object : ModifierVisitor<Void>() {
            // 1. 변수 선언 리네이밍 (int x = 0;)
            override fun visit(n: VariableDeclarator, arg: Void?): Visitable {
                val rename = validRenames[n.nameAsString]
                if (rename != null && rename.suggestedName.isNotEmpty()) {
                    n.setName(rename.suggestedName)
                    logger.debug("  Renamed variable declaration: ${rename.originalName} -> ${rename.suggestedName}")
                }
                return super.visit(n, arg)
            }

            // 2. for-each 루프 변수 리네이밍 (for (byte[] bArr2 : list))
            override fun visit(n: ForEachStmt, arg: Void?): Visitable {
                val variable = n.variable.variables.firstOrNull()
                if (variable != null) {
                    val rename = validRenames[variable.nameAsString]
                    if (rename != null && rename.suggestedName.isNotEmpty()) {
                        variable.setName(rename.suggestedName)
                        logger.debug("  Renamed foreach variable: ${rename.originalName} -> ${rename.suggestedName}")
                    }
                }
                return super.visit(n, arg)
            }

            // 3. catch 파라미터 리네이밍 (catch (Exception e))
            override fun visit(n: CatchClause, arg: Void?): Visitable {
                val param = n.parameter
                val rename = validRenames[param.nameAsString]
                if (rename != null && rename.suggestedName.isNotEmpty()) {
                    param.setName(rename.suggestedName)
                    logger.debug("  Renamed catch parameter: ${rename.originalName} -> ${rename.suggestedName}")
                }
                return super.visit(n, arg)
            }

            // 4. 변수 사용 리네이밍 (x + 1)
            override fun visit(n: NameExpr, arg: Void?): Visitable {
                val rename = validRenames[n.nameAsString]
                if (rename != null && rename.suggestedName.isNotEmpty()) {
                    n.setName(rename.suggestedName)
                    logger.debug("  Renamed variable usage: ${rename.originalName} -> ${rename.suggestedName}")
                }
                return super.visit(n, arg)
            }
        }, null)

        return validRenames
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
    data class Success(
        val entry: RenameEntry,
        val actualVariableRenames: Map<String, String> = emptyMap()  // 실제 적용된 변수 리네임
    ) : RenameResult()
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

/**
 * 리네이밍 충돌 검사 결과
 */
data class RenameConflictResult(
    val hasConflict: Boolean,
    val conflictingName: String? = null,
    val suggestedAlternative: String? = null,
    val reason: String? = null
)
