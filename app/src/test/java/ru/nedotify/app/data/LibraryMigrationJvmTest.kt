package ru.nedotify.app.data

import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Execute the production migration SQL against real SQLite, then compare with Room's schema. */
class LibraryMigrationJvmTest {
    @Test fun everyReleasedSchemaMigratesWithoutLosingLibraryData() {
        for(version in 1..3) {
            DriverManager.getConnection("jdbc:sqlite::memory:").use {db->
                execute(db,"PRAGMA foreign_keys=ON")
                createSchema(db,version)
                execute(db,"""INSERT INTO tracks(id,title,artist,album,durationMs,source,sourceId,sourceUrl,localUri,coverUrl,favorite,addedAt,mimeType)
                    VALUES('track','Title','Artist','Album',180000,'local','','','content://retained','',1,123,'audio/mpeg')""")
                execute(db,"INSERT INTO playlists VALUES('playlist','Retained',123)")
                execute(db,"INSERT INTO playlist_tracks VALUES('playlist','track',0)")
                execute(db,"INSERT INTO history VALUES('session','track',123,456,1)")
                if(version>=2)execute(db,"INSERT INTO offline_downloads VALUES('track','generation','complete',10,20,50.0,'','uri','audio/mpeg',123)")
                if(version>=3)execute(db,"INSERT INTO smart_playlists VALUES('smart','Мой выбор','{}',123)")
                val adapter=Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,arrayOf(SupportSQLiteDatabase::class.java)) {_,method,args->
                    if(method.name!="execSQL" || args?.size!=1)throw AssertionError("Unsupported migration method ${method.name}")
                    execute(db,args[0] as String);null
                } as SupportSQLiteDatabase
                if(version==1)LibraryDatabase.MIGRATION_1_2.migrate(adapter)
                if(version<=2)LibraryDatabase.MIGRATION_2_3.migrate(adapter)
                LibraryDatabase.MIGRATION_3_4.migrate(adapter)
                DriverManager.getConnection("jdbc:sqlite::memory:").use {fresh->
                    createSchema(fresh,4)
                    assertEquals("schema v$version → v4",signature(fresh),signature(db))
                }
                assertEquals(listOf(listOf("content://retained","1","0","0","0")),
                    query(db,"SELECT localUri,favorite,importedPlayCount,importedLastPlayed,importedDownloaded FROM tracks"))
                assertEquals("456",query(db,"SELECT listenedMs FROM history").single().single())
                assertEquals("the playlist entry of the track survives","playlist",
                    query(db,"SELECT playlistId FROM playlist_tracks").single().single())
                if(version>=2)assertEquals("the offline download of the track survives","complete",
                    query(db,"SELECT state FROM offline_downloads").single().single())
                if(version>=3)assertEquals("the smart playlist survives","Мой выбор",
                    query(db,"SELECT name FROM smart_playlists").single().single())
                execute(db,"INSERT INTO provider_mappings VALUES('track','youtube','abcdefghijk','Title','Artist','Album',180000)")
                execute(db,"INSERT INTO local_track_aliases VALUES('portable-local:alias','track')")
                execute(db,"DELETE FROM tracks WHERE id='track'")
                assertTrue(query(db,"SELECT * FROM playlist_tracks").isEmpty())
                assertTrue(query(db,"SELECT * FROM provider_mappings").isEmpty())
                assertTrue(query(db,"SELECT * FROM local_track_aliases").isEmpty())
                assertTrue(query(db,"SELECT * FROM offline_downloads").isEmpty())
                assertTrue("the history has no foreign key: the delete path removes it itself",
                    query(db,"SELECT * FROM history").isNotEmpty())
                assertTrue(query(db,"PRAGMA foreign_key_check").isEmpty())
            }
        }
    }

    /**
     * The exported schema must describe the entities of the module.
     *
     * Room writes the schema file from the entity declarations, so a schema that was edited by
     * hand - or an entity that was changed without a new export - is a drift between the two.
     * The migration guard above compares the *migration* with the exported schema; this check
     * compares the exported schema with the *entities*.
     */
    @Test fun theExportedSchemaDescribesTheEntitiesOfTheModule() {
        val entities=entityColumns(source("ru/nedotify/app/data/Models.kt"))
        val exported=schema(4).getJSONArray("entities")
        val tables=mutableSetOf<String>()
        for(index in 0 until exported.length()) {
            val entity=exported.getJSONObject(index)
            val table=entity.getString("tableName")
            tables+=table
            val fields=entity.getJSONArray("fields")
            val columns=mutableSetOf<String>()
            for(field in 0 until fields.length())columns+=fields.getJSONObject(field).getString("columnName")
            assertEquals("the columns of $table",entities[table],columns)
        }
        assertEquals("the tables of the database",entities.keys,tables)
    }

    /** The column names of every entity of the module source, by table name. */
    private fun entityColumns(source:String):Map<String,Set<String>> {
        val result=mutableMapOf<String,Set<String>>()
        var index=0
        while(true) {
            val at=source.indexOf("@Entity(",index)
            if(at<0)break
            val table=Regex("tableName\\s*=\\s*\"([^\"]+)\"").find(source,at)?.groupValues?.get(1)
            val open=source.indexOf("(",source.indexOf("data class ",at))
            val close=matching(source,open)
            if(table!=null)result[table]=parameters(source.substring(open+1,close)).toSet()
            index=close
        }
        return result
    }

    /** The index of the parenthesis that closes the one at [open]. */
    private fun matching(source:String,open:Int):Int {
        var depth=0
        for(index in open until source.length) {
            when(source[index]) {
                '('->depth++
                ')'->{depth--;if(depth==0)return index}
            }
        }
        throw AssertionError("the parentheses of the entity constructor do not close")
    }

    /** The names of the constructor parameters, without their annotations. */
    private fun parameters(body:String):List<String> {
        val parts=mutableListOf<String>();var depth=0;var start=0
        body.forEachIndexed {index,char->
            when(char) {
                '(','[','{'->depth++
                ')',']','}'->depth--
                ','->if(depth==0){parts+=body.substring(start,index);start=index+1}
            }
        }
        parts+=body.substring(start)
        return parts.map {part->
            val plain=Regex("@[A-Za-z_][A-Za-z0-9_]*(\\s*\\([^)]*\\))?").replace(part,"")
            plain.substringBefore(':').trim().removePrefix("val ").removePrefix("var ").trim()
        }.filter {it.isNotEmpty()}
    }

    /** The source of a file of the module, found from the working directory. */
    private fun source(path:String):String {
        var root=File("").absoluteFile
        while(root.parentFile!=null) {
            val file=File(root,"src/main/java/$path")
            if(file.isFile)return file.readText()
            root=root.parentFile
        }
        throw AssertionError("the source file $path was not found")
    }

    private fun createSchema(db:Connection,version:Int) {
        val entities=schema(version).getJSONArray("entities")
        for(index in 0 until entities.length()) {
            val entity=entities.getJSONObject(index);val name=entity.getString("tableName")
            execute(db,entity.getString("createSql").replace("\${TABLE_NAME}",name))
            val indices=entity.optJSONArray("indices") ?: continue
            for(item in 0 until indices.length())execute(db,indices.getJSONObject(item).getString("createSql").replace("\${TABLE_NAME}",name))
        }
    }

    private fun signature(db:Connection):Map<String,List<List<String?>>> = buildMap {
        for(row in query(db,"SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            val name=row.single()!!
            put("$name columns",query(db,"PRAGMA table_info('$name')").map {it.drop(1)}.sortedBy {it.first()})
            put("$name foreign keys",query(db,"PRAGMA foreign_key_list('$name')").map {it.drop(2)}.sortedBy {it.joinToString()})
            val indices=query(db,"PRAGMA index_list('$name')").filter {it[3]!="pk"}
            for(index in indices)put("$name index ${index[1]} ${index[2]}",query(db,"PRAGMA index_info('${index[1]}')").map {listOf(it[0],it[2])})
        }
    }

    private fun schema(version:Int):JSONObject {
        var directory=File("").absoluteFile
        while(true) {
            val file=File(directory,"schemas/ru.nedotify.app.data.LibraryDatabase/$version.json")
            if(file.isFile)return JSONObject(file.readText()).getJSONObject("database")
            directory=directory.parentFile ?: throw AssertionError("Room schema $version not found")
        }
    }
    private fun execute(db:Connection,sql:String)=db.createStatement().use {it.execute(sql);Unit}
    private fun query(db:Connection,sql:String):List<List<String?>> = db.createStatement().use {statement->
        statement.executeQuery(sql).use {cursor->buildList {while(cursor.next())add((1..cursor.metaData.columnCount).map {cursor.getString(it)})}}
    }
}
