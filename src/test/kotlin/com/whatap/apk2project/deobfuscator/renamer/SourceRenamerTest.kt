package com.whatap.apk2project.deobfuscator.renamer

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SourceRenamerTest {

    private lateinit var renamer: SourceRenamer

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        renamer = SourceRenamer()
    }

    @Test
    fun `should detect rename conflict when name already exists`() {
        // Given
        val javaFile = File(tempDir, "ConflictTest.java")
        javaFile.writeText("""
            public class ConflictTest {
                public void existingMethod() {}
                public void anotherMethod() {}
            }
        """.trimIndent())

        // When
        val result = renamer.checkRenameConflict(
            file = javaFile,
            originalName = "anotherMethod",
            newName = "existingMethod",  // Same as existing method
            signature = "()"
        )

        // Then
        assertTrue(result.hasConflict)
        assertEquals("existingMethod", result.conflictingName)
        assertNotNull(result.suggestedAlternative)
        assertTrue(result.suggestedAlternative!!.startsWith("existingMethod"))
    }

    @Test
    fun `should not detect conflict when name is unique`() {
        // Given
        val javaFile = File(tempDir, "NoConflict.java")
        javaFile.writeText("""
            public class NoConflict {
                public void method1() {}
                public void method2() {}
            }
        """.trimIndent())

        // When
        val result = renamer.checkRenameConflict(
            file = javaFile,
            originalName = "method1",
            newName = "renamedMethod",  // Unique name
            signature = "()"
        )

        // Then
        assertFalse(result.hasConflict)
        assertNull(result.conflictingName)
        assertNull(result.suggestedAlternative)
    }

    @Test
    fun `should suggest alternative name with suffix`() {
        // Given
        val javaFile = File(tempDir, "SuffixTest.java")
        javaFile.writeText("""
            public class SuffixTest {
                public void calculate() {}
                public void calculate2() {}
                public void toRename() {}
            }
        """.trimIndent())

        // When
        val result = renamer.checkRenameConflict(
            file = javaFile,
            originalName = "toRename",
            newName = "calculate",
            signature = "()"
        )

        // Then
        assertTrue(result.hasConflict)
        assertEquals("calculate3", result.suggestedAlternative)  // 2 already exists
    }

    @Test
    fun `updateReferences should update method calls`() {
        // Given
        val callerFile = File(tempDir, "Caller.java")
        callerFile.writeText("""
            public class Caller {
                public void callOther() {
                    SomeClass obj = new SomeClass();
                    obj.oldMethodName();
                    obj.oldMethodName();
                }
            }
        """.trimIndent())

        // When
        val result = renamer.updateReferences(
            files = listOf(callerFile),
            originalName = "oldMethodName",
            newName = "newMethodName",
            type = RenameType.METHOD
        )

        // Then
        assertEquals(1, result.count)
        val updatedContent = callerFile.readText()
        assertTrue(updatedContent.contains("newMethodName"))
        assertFalse(updatedContent.contains("oldMethodName"))
    }

    @Test
    fun `updateReferences should handle class references with generics`() {
        // Given
        val referencingFile = File(tempDir, "GenericUser.java")
        referencingFile.writeText("""
            import java.util.List;

            public class GenericUser {
                private List<OldClass> items;
                private Map<String, OldClass> map;

                public OldClass getItem() {
                    return new OldClass();
                }
            }
        """.trimIndent())

        // When
        val result = renamer.updateReferences(
            files = listOf(referencingFile),
            originalName = "OldClass",
            newName = "NewClass",
            type = RenameType.CLASS
        )

        // Then
        assertEquals(1, result.count)
        val updatedContent = referencingFile.readText()
        assertTrue(updatedContent.contains("List<NewClass>"))
        assertTrue(updatedContent.contains("Map<String, NewClass>"))
        assertTrue(updatedContent.contains("new NewClass()"))
    }

    @Test
    fun `updateReferences should handle array types`() {
        // Given
        val arrayFile = File(tempDir, "ArrayUser.java")
        arrayFile.writeText("""
            public class ArrayUser {
                private OldClass[] items;

                public OldClass[] getItems() {
                    return new OldClass[10];
                }
            }
        """.trimIndent())

        // When
        val result = renamer.updateReferences(
            files = listOf(arrayFile),
            originalName = "OldClass",
            newName = "NewClass",
            type = RenameType.CLASS
        )

        // Then
        assertEquals(1, result.count)
        val updatedContent = arrayFile.readText()
        assertTrue(updatedContent.contains("NewClass[]"))
        assertFalse(updatedContent.contains("OldClass[]"))
    }

    @Test
    fun `updateReferences should handle reflection patterns`() {
        // Given
        val reflectionFile = File(tempDir, "ReflectionUser.java")
        reflectionFile.writeText("""
            public class ReflectionUser {
                public void useReflection() {
                    Method m = cls.getMethod("oldMethod", String.class);
                    Field f = cls.getField("oldField");
                }
            }
        """.trimIndent())

        // When - Update method reference
        renamer.updateReferences(
            files = listOf(reflectionFile),
            originalName = "oldMethod",
            newName = "newMethod",
            type = RenameType.METHOD
        )

        // Then
        val content = reflectionFile.readText()
        assertTrue(content.contains("\"newMethod\""))
    }

    @Test
    fun `invalidateCache should clear cached names`() {
        // Given
        val javaFile = File(tempDir, "CacheInvalidate.java")
        javaFile.writeText("""
            public class CacheInvalidate {
                public void method1() {}
            }
        """.trimIndent())

        // First check to populate cache
        renamer.checkRenameConflict(javaFile, "method1", "newMethod", "()")

        // Modify file
        javaFile.writeText("""
            public class CacheInvalidate {
                public void method1() {}
                public void newMethod() {}
            }
        """.trimIndent())

        // Without invalidation, cache would have old state
        renamer.invalidateCache(javaFile)

        // When
        val result = renamer.checkRenameConflict(javaFile, "method1", "newMethod", "()")

        // Then - Should detect conflict with newly added method
        assertTrue(result.hasConflict)
    }

    @Test
    fun `should export rename history`() {
        // Given
        val historyFile = File(tempDir, "history.md")

        // When
        renamer.exportHistory(historyFile)

        // Then
        assertTrue(historyFile.exists())
        val content = historyFile.readText()
        assertTrue(content.contains("Deobfuscation Rename History"))
    }
}
