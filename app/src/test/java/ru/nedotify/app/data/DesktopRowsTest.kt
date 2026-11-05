package ru.nedotify.app.data

import org.junit.Assert.*
import org.junit.Test

/**
 * The rows of a desktop table.
 *
 * The importer reads up to 222,100 rows of one copy. The rows are the largest allocation of an
 * import, so they are not a map each: a row is a list of values under a shared list of column
 * names. The row is still a map, because the converter reads the columns by name.
 */
class DesktopRowsTest {
    private val table=DesktopRows.of(listOf("id","title","is_favorite","added_at"))

    @Test fun aRowIsAReadableMap() {
        val row=table.row(arrayOf<Any?>(1L,"Песня",1L,"2026-10-01 12:30:00"))
        assertEquals(4,row.size)
        assertEquals(1L,row["id"])
        assertEquals("Песня",row["title"])
        assertEquals(1L,row["is_favorite"])
        assertNull("a column that the table does not have",row["нет такого"])
        assertTrue(row.containsKey("added_at"))
        assertFalse(row.containsKey("нет такого"))
        assertEquals(setOf("id","title","is_favorite","added_at"),row.keys)
    }

    @Test fun everyRowOfTheTableSharesItsColumns() {
        val first=table.row(arrayOf<Any?>(1L,"Песня",1L,null))
        val second=table.row(arrayOf<Any?>(2L,"Другая",0L,null))
        assertEquals(first.keys,second.keys)
        assertEquals("Песня",first["title"])
        assertEquals("Другая",second["title"])
    }
}
