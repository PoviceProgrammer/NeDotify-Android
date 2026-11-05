package ru.nedotify.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** A byte-limited HTTP response can still contain enough nesting to overflow a DOM parser. */
private fun sourceJson(body:String):Any {
    if(body.length>4*1024*1024)throw SourceFailure("Ответ источника слишком большой")
    var depth=0;var quoted=false;var escaped=false
    body.forEach {char->
        if(quoted) {
            if(escaped)escaped=false else if(char=='\\')escaped=true else if(char=='"')quoted=false
        } else when(char) {
            '"'->quoted=true
            '[','{'->{depth++;if(depth>40)throw SourceFailure("Ответ источника слишком сложный")}
            ']','}'->{depth--;if(depth<0)throw SourceFailure("Источник вернул некорректный JSON")}
        }
    }
    if(quoted || depth!=0)throw SourceFailure("Источник вернул некорректный JSON")
    return try {
        val parser=JSONTokener(body);val value=parser.nextValue()
        if(parser.nextClean()!=0.toChar())throw SourceFailure("Источник вернул некорректный JSON")
        value
    } catch(error:SourceFailure) {throw error}
    catch(_:Exception) {throw SourceFailure("Источник вернул некорректный JSON")}
}
internal fun sourceObject(body:String):JSONObject=sourceJson(body) as? JSONObject?:throw SourceFailure("Источник не вернул объект JSON")
internal fun sourceArray(body:String):JSONArray=sourceJson(body) as? JSONArray?:throw SourceFailure("Источник не вернул список JSON")
