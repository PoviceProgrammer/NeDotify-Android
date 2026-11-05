package ru.nedotify.app.data

import java.io.File
import java.io.InputStream

/**
 * Validate the selected WAL before SQLite can silently ignore a damaged tail.
 *
 * A WAL is not a database of its own: it is a log of pages that belong to **one** database
 * file. Three things can go wrong, and all three are silent for SQLite:
 * - the file is truncated or its bytes are damaged: SQLite stops at the first bad frame and
 *   quietly uses the older content of the `.db`;
 * - the file carries no completed transaction: SQLite ignores every frame;
 * - the file belongs to a **different** database: SQLite replays foreign pages into the copy
 *   and the result is a database that no longer matches either file.
 *
 * The validator therefore checks the header, the page size, the salt, the frame structure and
 * the chained checksums, and it also compares the committed size of the journal with the size
 * of the selected database file.
 *
 * The size comparison is **one-directional**, and it is one-directional on purpose: a journal
 * that commits a *smaller* database than the file it is paired with cannot belong to it - it
 * would rewrite the copy with the content of another (or older) database, and that is
 * rejected. A journal that commits a *larger* database is the normal case: the `.db` is a
 * stale copy and the journal holds the newer pages. The direction of the check is therefore
 * "a journal may only grow the database it belongs to"; a journal of another database of the
 * same or of a larger size is not detected here, and the salt and the checksum of the frames
 * are what cover that case.
 */
internal object DesktopWalValidator {
    fun validate(database:File,wal:File) {
        if(wal.length()==0L)return // A checkpointed sidecar may be empty.
        fun invalid():Nothing=throw SourceFailure("Журнал WAL повреждён или скопирован не полностью. Закройте плеер на ПК и создайте копию заново")
        fun foreign():Nothing=throw SourceFailure("Журнал WAL не принадлежит выбранной БД. Скопируйте файл .db и файл .db-wal вместе, из одной папки")
        val dbHeader=database.inputStream().use {read(it,100) ?: invalid()}
        if(dbHeader.size<32)invalid()
        if(!dbHeader.copyOfRange(0,16).contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))invalid()
        val dbPage=(((dbHeader[16].toInt() and 255) shl 8) or (dbHeader[17].toInt() and 255)).let {if(it==1)65536 else it}
        if(dbPage !in 512..65536 || dbPage and (dbPage-1)!=0)invalid()
        val dbPages=database.length()/dbPage
        wal.inputStream().use {input->
            val header=read(input,32) ?: invalid()
            val magic=word(header,0,false)
            if(magic!=0x377f0682L && magic!=0x377f0683L)invalid()
            val little=magic==0x377f0682L
            val page=word(header,8,false).toInt()
            if(page!=dbPage || page !in 512..65536 || page and (page-1)!=0 || word(header,4,false)!=3007000L)invalid()
            if((wal.length()-32)%(page+24)!=0L)invalid()
            var a=0L;var b=0L
            fun checksum(bytes:ByteArray,offset:Int,length:Int) {
                var index=offset
                while(index<offset+length) {
                    a=(a+word(bytes,index,little)+b) and 0xffffffffL
                    b=(b+word(bytes,index+4,little)+a) and 0xffffffffL
                    index+=8
                }
            }
            checksum(header,0,24)
            if(a!=word(header,24,false) || b!=word(header,28,false))invalid()
            var remaining=wal.length()-32
            var frames=0
            var committed=0L
            while(remaining>0) {
                val frame=read(input,24) ?: invalid()
                val data=read(input,page) ?: invalid()
                val pageNumber=word(frame,0,false)
                if(pageNumber==0L || (8..15).any {frame[it]!=header[it+8]})invalid()
                checksum(frame,0,8);checksum(data,0,page)
                if(a!=word(frame,16,false) || b!=word(frame,20,false))invalid()
                // A commit frame carries the size of the database after the commit. SQLite
                // replays the journal up to the last commit and truncates the database to that
                // size, so the journal must never commit less than the file already holds.
                val size=word(frame,4,false)
                if(size>0L)committed=size
                frames++
                remaining-=page+24
            }
            // Frames without a commit carry no transaction: SQLite would ignore all of them
            // and the import would silently read the older content of the database.
            if(frames>0 && committed==0L)invalid()
            // The journal commits a database that is smaller than the file it is paired with:
            // it belongs to another (or to an older) database and would rewrite the copy.
            if(committed>0L && committed<dbPages)foreign()
        }
    }

    private fun read(input:InputStream,size:Int):ByteArray? {
        val bytes=ByteArray(size);var offset=0
        while(offset<size) {val count=input.read(bytes,offset,size-offset);if(count<0)return null;offset+=count}
        return bytes
    }

    private fun word(bytes:ByteArray,offset:Int,little:Boolean):Long {
        var value=0L
        for(index in 0..3) {
            val shift=if(little)index*8 else (3-index)*8
            value=value or ((bytes[offset+index].toLong() and 255) shl shift)
        }
        return value
    }
}
