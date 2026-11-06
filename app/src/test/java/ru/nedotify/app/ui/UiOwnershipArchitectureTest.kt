package ru.nedotify.app.ui

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Architectural guard complements the navigation/search/connection behavior tests. */
class UiOwnershipArchitectureTest {
    @Test fun theRemovedAggregateViewModelIsAbsentFromSourceAndCompiledProductionClasses() {
        val source=productionRoot()
        val aggregateName="Music"+"ViewModel"
        assertFalse(File(source,"ui/$aggregateName.kt").exists())
        val references=source.walkTopDown().filter {it.isFile && it.extension=="kt"}.filter {file->
            val code=file.readText().replace(Regex("/\\*.*?\\*/",RegexOption.DOT_MATCHES_ALL),"")
                .replace(Regex("//[^\\n]*"),"")
            Regex("\\b$aggregateName\\b").containsMatchIn(code)
        }.map {it.relativeTo(source).invariantSeparatorsPath}.toList()
        assertTrue("Production still references the aggregate owner: $references",references.isEmpty())
        assertNull(javaClass.classLoader!!.getResource("ru/nedotify/app/ui/$aggregateName.class"))
    }

    private fun productionRoot():File {
        var directory=File("").absoluteFile
        while(true) {
            for(relative in listOf("src/main/java/ru/nedotify/app","app/src/main/java/ru/nedotify/app")) {
                val candidate=File(directory,relative)
                if(candidate.isDirectory)return candidate
            }
            directory=directory.parentFile?:throw AssertionError("Android production sources not found")
        }
    }
}
