package ru.nedotify.app.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.sql.DriverManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DesktopWalValidatorTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun completeRealSqliteWalIsAcceptedAndTruncationOrChecksumDamageIsRejected() {
        val source=File(temporary.root,"source.db")
        DriverManager.getConnection("jdbc:sqlite:${source.absolutePath}").use {db->
            db.createStatement().use {statement->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA wal_autocheckpoint=0")
                statement.execute("CREATE TABLE tracks(id INTEGER PRIMARY KEY,title TEXT)")
                statement.execute("INSERT INTO tracks(title) VALUES('newest committed track')")
            }
            val copy=source.copyTo(File(temporary.root,"copy.db"))
            val wal=File(source.path+"-wal").copyTo(File(temporary.root,"copy.db-wal"))
            DesktopWalValidator.validate(copy,wal)
            val valid=wal.readBytes()
            wal.writeBytes(valid.copyOf(valid.size-1))
            assertThrows(SourceFailure::class.java) {DesktopWalValidator.validate(copy,wal)}
            valid[valid.lastIndex]=(valid.last().toInt() xor 1).toByte();wal.writeBytes(valid)
            assertThrows(SourceFailure::class.java) {DesktopWalValidator.validate(copy,wal)}
        }
    }

    @Test fun ordinaryNonWalFileCannotBeSilentlyIgnoredAsASelectedJournal() {
        val database=File(temporary.root,"db").apply {writeBytes(ByteArray(100).also {it[16]=16})}
        val wal=File(temporary.root,"wal").apply {writeText("not a SQLite write ahead log")}
        assertThrows(SourceFailure::class.java) {DesktopWalValidator.validate(database,wal)}
    }

    /**
     * A journal of another database is a valid journal - it simply belongs to a different file.
     * SQLite would replay its pages into the copy and quietly produce a database that matches
     * neither file, so the importer has to refuse the pair.
     */
    @Test fun theJournalOfAnotherDatabaseIsRejected() {
        val small=File(temporary.root,"small.db")
        val large=File(temporary.root,"large.db")
        DriverManager.getConnection("jdbc:sqlite:${large.absolutePath}").use {db->
            db.createStatement().use {statement->
                statement.execute("PRAGMA journal_mode=DELETE")
                statement.execute("CREATE TABLE tracks(id INTEGER PRIMARY KEY,title TEXT)")
                for(index in 0 until 200)statement.execute("INSERT INTO tracks(title) VALUES('${"x".repeat(400)}')")
            }
        }
        val journal:ByteArray
        DriverManager.getConnection("jdbc:sqlite:${small.absolutePath}").use {db->
            db.createStatement().use {statement->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA wal_autocheckpoint=0")
                statement.execute("CREATE TABLE tracks(id INTEGER PRIMARY KEY,title TEXT)")
                statement.execute("INSERT INTO tracks(title) VALUES('a')")
            }
            journal=File(small.path+"-wal").readBytes()
        }
        val wal=File(temporary.root,"large.db-wal").apply {writeBytes(journal)}
        val error=assertThrows(SourceFailure::class.java) {DesktopWalValidator.validate(large,wal)}
        assertTrue("the message must name the mismatch: ${error.message}",
            error.message!!.contains("не принадлежит"))
    }

    @Test fun aJournalWithoutACompletedTransactionIsRejected() {
        val database=database(pages=1)
        val wal=File(temporary.root,"db-wal").apply {writeBytes(journal(commits=false,size=0))}
        assertThrows(SourceFailure::class.java) {DesktopWalValidator.validate(database,wal)}
    }

    @Test fun aJournalThatCommitsLessThanTheFileHoldsIsRejected() {
        val database=database(pages=2)
        val wal=File(temporary.root,"db-wal").apply {writeBytes(journal(commits=true,size=1))}
        val error=assertThrows(SourceFailure::class.java) {DesktopWalValidator.validate(database,wal)}
        assertTrue(error.message!!.contains("не принадлежит"))
    }

    @Test fun aJournalThatCommitsTheSizeOfTheFileIsAccepted() {
        val database=database(pages=2)
        val wal=File(temporary.root,"db-wal").apply {writeBytes(journal(commits=true,size=2))}
        DesktopWalValidator.validate(database,wal)
    }

    /** A file with the SQLite header of [pages] pages; the content is not a database. */
    private fun database(pages:Int):File {
        val bytes=ByteArray(4096*pages)
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        bytes[16]=0x10;bytes[17]=0x00
        return File(temporary.root,"db").apply {writeBytes(bytes)}
    }

    /** A journal of one page, with or without a commit frame. */
    private fun journal(commits:Boolean,size:Long):ByteArray {
        val pageSize=4096
        val header=ByteArray(32)
        put(header,0,0x377f0682L,true);put(header,4,3007000L,true);put(header,8,pageSize.toLong(),true)
        put(header,12,0L,true);put(header,16,7L,true);put(header,20,8L,true)
        var s1=0L;var s2=0L
        fun checksum(bytes:ByteArray,offset:Int,length:Int) {
            var index=offset
            while(index<offset+length) {
                s1=(s1+little(bytes,index)+s2) and 0xffffffffL
                s2=(s2+little(bytes,index+4)+s1) and 0xffffffffL
                index+=8
            }
        }
        checksum(header,0,24);put(header,24,s1,true);put(header,28,s2,true)
        val out=ByteArrayOutputStream();out.write(header)
        val frame=ByteArray(24)
        put(frame,0,1L,true);put(frame,4,if(commits)size else 0L,true);put(frame,8,7L,true);put(frame,12,8L,true)
        val data=ByteArray(pageSize){(it and 0xff).toByte()}
        checksum(frame,0,8);checksum(data,0,pageSize)
        put(frame,16,s1,true);put(frame,20,s2,true)
        out.write(frame);out.write(data)
        return out.toByteArray()
    }

    private fun put(target:ByteArray,offset:Int,value:Long,bigEndian:Boolean) {
        for(index in 0..3) {
            val shift=if(bigEndian)(3-index)*8 else index*8
            target[offset+index]=((value shr shift) and 255).toByte()
        }
    }
    private fun little(bytes:ByteArray,offset:Int):Long =
        (bytes[offset].toLong() and 255) or ((bytes[offset+1].toLong() and 255) shl 8) or
            ((bytes[offset+2].toLong() and 255) shl 16) or ((bytes[offset+3].toLong() and 255) shl 24)
}
