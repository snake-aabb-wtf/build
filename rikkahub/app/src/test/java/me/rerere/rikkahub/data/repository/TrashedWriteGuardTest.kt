package me.rerere.rikkahub.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashedWriteGuardTest {
    @Test
    fun `trashed row skips write`() = assertTrue(shouldSkipTrashedWrite(existingDeletedAt = 1L))

    @Test
    fun `visible row keeps write`() = assertFalse(shouldSkipTrashedWrite(existingDeletedAt = 0L))
}
