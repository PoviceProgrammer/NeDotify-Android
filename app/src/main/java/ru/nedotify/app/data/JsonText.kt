package ru.nedotify.app.data

import org.json.JSONObject

/** Same null semantics on Android's JSON implementation and the host JVM. */
fun JSONObject.optText(key: String, fallback: String = ""): String = when (val value = opt(key)) {
    null, JSONObject.NULL -> fallback
    is String -> value
    is Number, is Boolean -> value.toString()
    else -> fallback
}
