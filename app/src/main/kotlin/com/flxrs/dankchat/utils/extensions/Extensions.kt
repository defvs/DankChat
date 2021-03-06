package com.flxrs.dankchat.utils.extensions

import android.app.ActivityManager
import android.content.Context
import android.content.Context.ACTIVITY_SERVICE
import android.util.Log
import com.flxrs.dankchat.chat.menu.EmoteItem
import com.flxrs.dankchat.preferences.multientry.MultiEntryItem
import com.flxrs.dankchat.service.twitch.emote.GenericEmote
import com.flxrs.dankchat.service.twitch.message.Mention
import com.squareup.moshi.JsonAdapter
import java.util.regex.Pattern

fun List<GenericEmote>?.toEmoteItems(): List<EmoteItem> {
    return this?.groupBy { it.emoteType.title }
        ?.mapValues {
            val title = it.value.first().emoteType.title
            listOf(EmoteItem.Header(title)).plus(it.value.map { e -> EmoteItem.Emote(e) })
        }?.flatMap { it.value } ?: listOf()
}

fun List<MultiEntryItem.Entry?>.mapToMention(): List<Mention> = mapNotNull { entry ->
    entry?.let {
        when {
            it.isRegex -> try {
                Mention.RegexPhrase(it.entry.toPattern(Pattern.CASE_INSENSITIVE).toRegex(), it.matchUser)
            } catch (t: Throwable) {
                null
            }
            else -> Mention.Phrase(it.entry, it.matchUser)
        }
    }

}

fun Set<String>?.mapToMention(adapter: JsonAdapter<MultiEntryItem.Entry>?): List<Mention> {
    return this?.mapNotNull { adapter?.fromJson(it) }?.mapToMention().orEmpty()
}

inline fun <V> measureTimeValue(block: () -> V): Pair<V, Long> {
    val start = System.currentTimeMillis()
    return block() to System.currentTimeMillis() - start
}

inline fun <V> measureTimeAndLog(tag: String, toLoad: String, block: () -> V): V {
    val (result, time) = measureTimeValue(block)
    if (result != null) {
        Log.i(tag, "Loaded $toLoad in $time ms")
    } else {
        Log.i(tag, "Failed to load $toLoad ($time ms")
    }
    return result
}

@Suppress("DEPRECATION") // Deprecated for third party Services.
fun <T> Context.isServiceRunning(service: Class<T>) =
    (getSystemService(ACTIVITY_SERVICE) as ActivityManager)
        .getRunningServices(Integer.MAX_VALUE)
        .any { it.service.className == service.name }