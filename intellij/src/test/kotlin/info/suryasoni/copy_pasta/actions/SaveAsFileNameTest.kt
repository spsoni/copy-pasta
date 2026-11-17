package info.suryasoni.copy_pasta.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SaveAsFileNameTest {
    @Test
    fun `default name is local date and time, file-system safe and sortable`() {
        assertEquals("copy-pasta-2026-11-17-090507.txt", SaveAsFile.defaultFileName(LocalDateTime.of(2026, 11, 17, 9, 5, 7)))
        val name = SaveAsFile.defaultFileName()
        assertTrue(name, name.none { it in "/\\:*?\"<>| " })
        val earlier = SaveAsFile.defaultFileName(LocalDateTime.of(2026, 1, 2, 23, 59, 59))
        val later = SaveAsFile.defaultFileName(LocalDateTime.of(2026, 10, 2, 0, 0, 0))
        assertTrue(earlier < later)
    }
}
