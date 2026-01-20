package com.whatap.apk2project.deobfuscator.renamer

import com.github.javaparser.JavaParser
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.VariableDeclarator
import org.slf4j.LoggerFactory
import java.io.File

/**
 * 메서드 인덱서
 *
 * Java 소스 파일을 파싱해서 메서드 정보를 추출하고 인덱스 파일에 저장
 */
class MethodIndexer {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val parser = JavaParser()

    /**
     * 파일을 인덱싱하고 인덱스 파일에 저장
     */
    fun indexFile(sourceFile: File, indexFile: File): MethodIndex {
        logger.debug("Indexing file: ${sourceFile.absolutePath}")

        val parseResult = parser.parse(sourceFile)
        if (!parseResult.isSuccessful) {
            throw RuntimeException("Parse failed: ${parseResult.problems.joinToString()}")
        }

        val cu = parseResult.result.get()
        val methods = mutableMapOf<String, MethodInfo>()

        cu.findAll(MethodDeclaration::class.java).forEach { method ->
            val methodName = method.nameAsString
            val className = method.findAncestor(com.github.javaparser.ast.body.ClassOrInterfaceDeclaration::class.java)
                .map { it.nameAsString }
                .orElse("Unknown")

            // 메서드 범위
            val startLine = 0  // TODO: 구현 필요
            val endLine = 0    // TODO: 구현 필요

            // 반환 타입
            val returnType = method.typeAsString ?: "void"

            // 파라미터
            val parameters = method.parameters.map { param ->
                ParameterInfo(
                    name = param.nameAsString,
                    type = param.typeAsString
                )
            }

            // 로컬 변수
            val localVariables = method.findAll(VariableDeclarator::class.java).map { varDecl ->
                VariableInfo(
                    name = varDecl.nameAsString,
                    type = varDecl.typeAsString,
                    line = 0  // TODO: 구현 필요
                )
            }

            methods[methodName] = MethodInfo(
                methodName = methodName,
                className = className,
                startLine = startLine,
                endLine = endLine,
                returnType = returnType,
                parameters = parameters,
                localVariables = localVariables
            )
        }

        val index = MethodIndex(
            filePath = sourceFile.absolutePath,
            lastModified = sourceFile.lastModified(),
            methods = methods
        )

        index.save(indexFile)
        logger.debug("Indexed ${methods.size} methods -> ${indexFile.absolutePath}")

        return index
    }

    /**
     * 인덱스 파일이 있고 최신이면 로드, 없거나 오래되었으면 다시 인덱싱
     */
    fun getOrCreateIndex(sourceFile: File, indexFile: File): MethodIndex {
        val existingIndex = MethodIndex.load(indexFile)

        return if (existingIndex != null && !existingIndex.isOutOfDate(sourceFile)) {
            logger.debug("Using existing index: ${indexFile.absolutePath}")
            existingIndex
        } else {
            indexFile.parentFile?.mkdirs()
            indexFile(sourceFile, indexFile)
        }
    }
}
