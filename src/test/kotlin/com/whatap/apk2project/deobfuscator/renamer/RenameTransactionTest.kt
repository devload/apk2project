package com.whatap.apk2project.deobfuscator.renamer

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RenameTransactionTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var sourceDir: File

    @BeforeEach
    fun setup() {
        sourceDir = File(tempDir, "source")
        sourceDir.mkdirs()
    }

    @Test
    fun `should commit changes successfully`() {
        // Given
        val file1 = File(sourceDir, "File1.java")
        file1.writeText("original content 1")

        val file2 = File(sourceDir, "File2.java")
        file2.writeText("original content 2")

        val transaction = RenameTransaction(sourceDir)

        // When
        transaction.registerModification(file1, "modified content 1")
        transaction.registerModification(file2, "modified content 2")
        val result = transaction.commit()

        // Then
        assertTrue(result.success)
        assertEquals(2, result.modifiedFiles.size)
        assertEquals("modified content 1", file1.readText())
        assertEquals("modified content 2", file2.readText())
    }

    @Test
    fun `should rollback on explicit call`() {
        // Given
        val file = File(sourceDir, "Rollback.java")
        file.writeText("original content")

        val transaction = RenameTransaction(sourceDir)
        transaction.registerModification(file, "modified content")

        // When
        transaction.rollback()

        // Then
        assertEquals("original content", file.readText())
        assertEquals(TransactionStatus.ROLLED_BACK, transaction.status)
    }

    @Test
    fun `should provide modified content during transaction`() {
        // Given
        val file = File(sourceDir, "ReadTest.java")
        file.writeText("original content")

        val transaction = RenameTransaction(sourceDir)

        // When - before modification
        val content1 = transaction.readContent(file)

        // Register modification
        transaction.registerModification(file, "modified content")

        // When - after modification
        val content2 = transaction.readContent(file)

        // Then
        assertEquals("original content", content1)
        assertEquals("modified content", content2)
        // Original file unchanged until commit
        assertEquals("original content", file.readText())
    }

    @Test
    fun `should track pending modifications count`() {
        // Given
        val file1 = File(sourceDir, "Count1.java")
        file1.writeText("content 1")
        val file2 = File(sourceDir, "Count2.java")
        file2.writeText("content 2")

        val transaction = RenameTransaction(sourceDir)

        // When
        assertEquals(0, transaction.pendingModifications)

        transaction.registerModification(file1, "new 1")
        assertEquals(1, transaction.pendingModifications)

        transaction.registerModification(file2, "new 2")
        assertEquals(2, transaction.pendingModifications)
    }

    @Test
    fun `should backup file only once for multiple modifications`() {
        // Given
        val file = File(sourceDir, "MultiMod.java")
        file.writeText("original content")

        val transaction = RenameTransaction(sourceDir)

        // When - multiple modifications
        transaction.registerModification(file, "content v1")
        transaction.registerModification(file, "content v2")
        transaction.registerModification(file, "content v3")

        transaction.commit()

        // Then
        assertEquals("content v3", file.readText())
    }

    @Test
    fun `should report transaction status correctly`() {
        // Given
        val file = File(sourceDir, "Status.java")
        file.writeText("content")

        val transaction = RenameTransaction(sourceDir)

        // Then
        assertEquals(TransactionStatus.ACTIVE, transaction.status)

        transaction.registerModification(file, "new content")
        transaction.commit()

        assertEquals(TransactionStatus.COMMITTED, transaction.status)
    }

    @Test
    fun `should reject operations after commit`() {
        // Given
        val file = File(sourceDir, "Reject.java")
        file.writeText("content")

        val transaction = RenameTransaction(sourceDir)
        transaction.registerModification(file, "new content")
        transaction.commit()

        // When/Then
        assertThrows(IllegalStateException::class.java) {
            transaction.registerModification(file, "another content")
        }
    }

    @Test
    fun `should reject operations after rollback`() {
        // Given
        val file = File(sourceDir, "RejectAfterRollback.java")
        file.writeText("content")

        val transaction = RenameTransaction(sourceDir)
        transaction.registerModification(file, "new content")
        transaction.rollback()

        // When/Then
        assertThrows(IllegalStateException::class.java) {
            transaction.registerModification(file, "another content")
        }
    }

    @Test
    fun `withRenameTransaction should auto-commit on success`() {
        // Given
        val file = File(sourceDir, "Auto.java")
        file.writeText("original")

        // When
        val result = withRenameTransaction(sourceDir) { transaction ->
            transaction.registerModification(file, "modified")
        }

        // Then
        assertTrue(result.success)
        assertEquals("modified", file.readText())
    }

    @Test
    fun `withRenameTransaction should auto-rollback on exception`() {
        // Given
        val file = File(sourceDir, "AutoRollback.java")
        file.writeText("original")

        // When
        val result = withRenameTransaction(sourceDir) { transaction ->
            transaction.registerModification(file, "modified")
            throw RuntimeException("Simulated failure")
        }

        // Then
        assertFalse(result.success)
        assertEquals("original", file.readText())  // Should be rolled back
    }

    @Test
    fun `should handle empty transaction`() {
        // Given
        val transaction = RenameTransaction(sourceDir)

        // When
        val result = transaction.commit()

        // Then
        assertTrue(result.success)
        assertTrue(result.modifiedFiles.isEmpty())
    }

    @Test
    fun `should handle non-existent file gracefully`() {
        // Given
        val nonExistent = File(sourceDir, "NonExistent.java")
        val transaction = RenameTransaction(sourceDir)

        // When
        val registered = transaction.registerModification(nonExistent, "content")

        // Then
        assertFalse(registered)
    }
}
