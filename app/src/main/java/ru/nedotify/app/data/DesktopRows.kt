package ru.nedotify.app.data

import java.util.AbstractMap.SimpleImmutableEntry

/**
 * A row of a desktop table, without a map per row.
 *
 * The importer reads up to 222,100 rows of one copy: 20,000 tracks, 2,000 playlists, 100,000
 * playlist entries, 100,000 history rows and 100 smart playlists. A `LinkedHashMap` per row
 * costs a map, a table of buckets and an entry per column; a row that is a list of values under
 * a shared list of column names costs a fraction of that. The rows are the largest allocation of
 * an import, and the peak of an import is the rows.
 *
 * The row is still a `Map`: the converter reads the columns by name, and the tests of the
 * converter build their fixtures as ordinary maps.
 */
internal class DesktopRows private constructor(internal val columns:Array<String>,internal val index:Map<String,Int>) {
    /** The names of the columns, as a set: the same set for every row of the table. */
    val keys:Set<String> = java.util.LinkedHashSet(columns.toList())
    fun row(cells:Array<Any?>):Map<String,Any?> = DesktopRowMap(this,cells)
    companion object {
        fun of(columns:List<String>):DesktopRows =
            DesktopRows(columns.toTypedArray(),columns.withIndex().associate { it.value to it.index })
    }
}

internal class DesktopRowMap(private val table:DesktopRows,private val cells:Array<Any?>) : Map<String,Any?> {
    override val size get()=cells.size
    override fun isEmpty()=cells.isEmpty()
    override fun containsKey(key:String)=table.index.containsKey(key)
    override fun containsValue(value:Any?)=cells.contains(value)
    override fun get(key:String):Any? {val at=table.index[key] ?: return null;return cells[at]}
    override val keys:Set<String> get()=table.keys
    override val values:Collection<Any?> get()=cells.toList()
    override val entries:Set<Map.Entry<String,Any?>> get() {
        val result=java.util.LinkedHashSet<Map.Entry<String,Any?>>(cells.size)
        table.columns.forEachIndexed { index,column -> result.add(SimpleImmutableEntry(column,cells[index])) }
        return result
    }
}
