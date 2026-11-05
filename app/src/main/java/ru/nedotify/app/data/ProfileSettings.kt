package ru.nedotify.app.data

import org.json.JSONArray
import java.util.Locale

data class UserProfile(val name: String = "Моя музыка", val bio: String = "Слушаю музыку, живу моментом", val avatarUri: String = "")

/** Bounded, local-only personalisation. It is deliberately excluded from library exports. */
object PreferenceValues {
    val accents = listOf("cyan", "blue", "violet", "mint", "system")

    /** The bounds of the model. The UI must not offer a value outside them. */
    const val MAX_NAME = 40
    const val MAX_BIO = 160

    fun profile(name: String, bio: String): UserProfile {
        val cleanName = name.trim(); val cleanBio = bio.trim()
        require(cleanName.isNotBlank() && cleanName.length <= MAX_NAME && cleanName.none { it.isISOControl() }) {
            "Имя должно содержать от 1 до $MAX_NAME символов"
        }
        require(cleanBio.length <= MAX_BIO && cleanBio.none { it.isISOControl() && it != '\n' }) {
            "Описание должно быть не длиннее $MAX_BIO символов"
        }
        return UserProfile(cleanName, cleanBio)
    }

    /**
     * The model's own message for a draft it refuses, or null when it accepts it.
     *
     * The UI shows this message instead of inventing one: the model is the
     * authority of what a profile may contain.
     */
    fun profileError(name: String, bio: String): String? =
        runCatching { profile(name, bio) }.exceptionOrNull()?.message

    /**
     * The text a profile field accepts: no control characters, at most
     * [limit] characters.
     *
     * The model refuses the control characters, so the field must not offer
     * them: a value the model will reject is a value the user cannot save.
     */
    fun profileField(text: String, limit: Int, allowNewline: Boolean = false): String =
        text.filterNot { it.isISOControl() && !(allowNewline && it == '\n') }.take(limit)

    fun rememberSearch(current: List<String>, query: String): List<String> {
        val clean = query.trim().take(200).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        if (clean.isBlank() || clean.any { it.isISOControl() }) return current.take(20)
        return (listOf(clean) + current).distinctBy { it.lowercase(Locale.ROOT) }.take(20)
    }

    fun encodeSearchHistory(values: List<String>): String = JSONArray(values.take(20)).toString()
    fun decodeSearchHistory(json: String): List<String> {
        if (json.length > 32_000) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            if (array.length() > 20) return emptyList()
            (0 until array.length()).mapNotNull { array.get(it) as? String }
                .filter { it.isNotBlank() && it.length <= 200 && it.none { char -> char.isISOControl() } }
                .distinctBy { it.lowercase(Locale.ROOT) }
        }.getOrDefault(emptyList())
    }
}

/**
 * The draft of the profile dialog.
 *
 * The fields are filtered to what the model accepts, so the write cannot fail
 * on a control character the user typed, and the model's own message is
 * carried with the draft.
 */
data class ProfileDraft(val name: String, val bio: String, val error: String? = null) {
    /** The draft the model accepts. */
    val valid: Boolean get() = error == null

    companion object {
        fun of(name: String, bio: String): ProfileDraft {
            val cleanName = PreferenceValues.profileField(name, PreferenceValues.MAX_NAME)
            val cleanBio = PreferenceValues.profileField(bio, PreferenceValues.MAX_BIO, allowNewline = true)
            return ProfileDraft(cleanName, cleanBio, PreferenceValues.profileError(cleanName, cleanBio))
        }
    }
}
