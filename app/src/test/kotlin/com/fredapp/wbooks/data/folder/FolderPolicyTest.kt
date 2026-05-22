package com.fredapp.wbooks.data.folder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderPolicyTest {
    @Test
    fun createRejectsLimitAndDuplicateNames() {
        val existing = (1..FolderPolicy.MAX_FOLDERS).map { "Folder $it" }

        val duplicate = FolderPolicy.validateCreate("folder 1", existing)
        assertFalse(duplicate.isValid)
        assertEquals("Folder already exists", duplicate.error)

        val overflow = FolderPolicy.validateCreate("Another", existing)
        assertFalse(overflow.isValid)
        assertEquals("Folder limit reached (${FolderPolicy.MAX_FOLDERS})", overflow.error)
    }

    @Test
    fun validateNameRejectsPathAndReservedNames() {
        assertNull(FolderPolicy.validateName("A/B").name)
        assertNull(FolderPolicy.validateName("CON").name)
        assertNull(FolderPolicy.validateName(".".trim()).name)
        assertNull(FolderPolicy.validateName("x".repeat(FolderPolicy.MAX_NAME_LENGTH + 1)).name)
    }

    @Test
    fun moveTargetUsesExistingFolderCasing() {
        val result = FolderPolicy.validateMoveTarget("fiction", listOf("Fiction"))

        assertTrue(result.isValid)
        assertEquals("Fiction", result.name)
    }
}
