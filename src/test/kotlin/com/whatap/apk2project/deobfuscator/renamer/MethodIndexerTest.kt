package com.whatap.apk2project.deobfuscator.renamer

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MethodIndexerTest {

    private lateinit var indexer: MethodIndexer

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        indexer = MethodIndexer()
    }

    @Test
    fun `should index simple Java file with one method`() {
        // Given
        val javaFile = File(tempDir, "SimpleClass.java")
        javaFile.writeText("""
            public class SimpleClass {
                public void simpleMethod() {
                    int x = 10;
                    String name = "test";
                }
            }
        """.trimIndent())

        val indexFile = File(tempDir, "index.json")

        // When
        val index = indexer.indexFile(javaFile, indexFile)

        // Then
        assertEquals(javaFile.absolutePath, index.filePath)
        assertTrue(index.methods.containsKey("simpleMethod"))

        val methodInfo = index.methods["simpleMethod"]!!
        assertEquals("simpleMethod", methodInfo.methodName)
        assertEquals("SimpleClass", methodInfo.className)
        assertEquals("void", methodInfo.returnType)
        assertTrue(methodInfo.startLine > 0)
        assertTrue(methodInfo.endLine >= methodInfo.startLine)
    }

    @Test
    fun `should extract line numbers correctly`() {
        // Given
        val javaFile = File(tempDir, "LineNumberTest.java")
        javaFile.writeText("""
            public class LineNumberTest {
                public int calculate(int a, int b) {
                    return a + b;
                }
            }
        """.trimIndent())

        val indexFile = File(tempDir, "index.json")

        // When
        val index = indexer.indexFile(javaFile, indexFile)

        // Then
        val methodInfo = index.methods["calculate"]!!
        assertEquals(2, methodInfo.startLine)  // Method starts at line 2
        assertEquals(4, methodInfo.endLine)    // Method ends at line 4
    }

    @Test
    fun `should extract method parameters`() {
        // Given
        val javaFile = File(tempDir, "ParamTest.java")
        javaFile.writeText("""
            public class ParamTest {
                public void process(String input, int count, boolean flag) {
                }
            }
        """.trimIndent())

        val indexFile = File(tempDir, "index.json")

        // When
        val index = indexer.indexFile(javaFile, indexFile)

        // Then
        val methodInfo = index.methods["process"]!!
        assertEquals(3, methodInfo.parameters.size)
        assertEquals("input", methodInfo.parameters[0].name)
        assertEquals("String", methodInfo.parameters[0].type)
        assertEquals("count", methodInfo.parameters[1].name)
        assertEquals("int", methodInfo.parameters[1].type)
        assertEquals("flag", methodInfo.parameters[2].name)
        assertEquals("boolean", methodInfo.parameters[2].type)
    }

    @Test
    fun `should extract local variables`() {
        // Given
        val javaFile = File(tempDir, "VarTest.java")
        javaFile.writeText("""
            public class VarTest {
                public void method() {
                    int counter = 0;
                    String message = "hello";
                    double result = 3.14;
                }
            }
        """.trimIndent())

        val indexFile = File(tempDir, "index.json")

        // When
        val index = indexer.indexFile(javaFile, indexFile)

        // Then
        val methodInfo = index.methods["method"]!!
        assertEquals(3, methodInfo.localVariables.size)

        val varNames = methodInfo.localVariables.map { it.name }
        assertTrue(varNames.contains("counter"))
        assertTrue(varNames.contains("message"))
        assertTrue(varNames.contains("result"))
    }

    @Test
    fun `should index multiple methods`() {
        // Given
        val javaFile = File(tempDir, "MultiMethod.java")
        javaFile.writeText("""
            public class MultiMethod {
                public void method1() {}
                public int method2() { return 0; }
                private String method3() { return ""; }
            }
        """.trimIndent())

        val indexFile = File(tempDir, "index.json")

        // When
        val index = indexer.indexFile(javaFile, indexFile)

        // Then
        assertEquals(3, index.methods.size)
        assertTrue(index.methods.containsKey("method1"))
        assertTrue(index.methods.containsKey("method2"))
        assertTrue(index.methods.containsKey("method3"))
    }

    @Test
    fun `should use cached index when file not modified`() {
        // Given
        val javaFile = File(tempDir, "CacheTest.java")
        javaFile.writeText("""
            public class CacheTest {
                public void testMethod() {}
            }
        """.trimIndent())

        val indexFile = File(tempDir, "index.json")

        // When
        val index1 = indexer.getOrCreateIndex(javaFile, indexFile)
        val index2 = indexer.getOrCreateIndex(javaFile, indexFile)

        // Then
        assertEquals(index1.filePath, index2.filePath)
        assertEquals(index1.methods.size, index2.methods.size)
    }

    @Test
    fun `should re-index when file is modified`() {
        // Given
        val javaFile = File(tempDir, "ModifyTest.java")
        javaFile.writeText("""
            public class ModifyTest {
                public void original() {}
            }
        """.trimIndent())

        val indexFile = File(tempDir, "index.json")

        // First indexing
        val index1 = indexer.getOrCreateIndex(javaFile, indexFile)
        assertEquals(1, index1.methods.size)
        assertTrue(index1.methods.containsKey("original"))

        // Modify file (ensure different timestamp)
        Thread.sleep(1100) // Wait to ensure different lastModified
        javaFile.writeText("""
            public class ModifyTest {
                public void modified() {}
                public void another() {}
            }
        """.trimIndent())

        // When
        val index2 = indexer.getOrCreateIndex(javaFile, indexFile)

        // Then
        assertEquals(2, index2.methods.size)
        assertTrue(index2.methods.containsKey("modified"))
        assertTrue(index2.methods.containsKey("another"))
    }
}
