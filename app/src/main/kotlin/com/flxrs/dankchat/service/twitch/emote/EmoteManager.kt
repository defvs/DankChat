package com.flxrs.dankchat.service.twitch.emote

import android.util.LruCache
import com.flxrs.dankchat.service.api.TwitchApi
import com.flxrs.dankchat.service.api.model.BadgeEntities
import com.flxrs.dankchat.service.api.model.EmoteEntities
import com.flxrs.dankchat.utils.extensions.supplementaryCodePointPositions
import kotlinx.coroutines.*
import pl.droidsonroids.gif.GifDrawable
import pl.droidsonroids.gif.MultiCallback
import java.util.concurrent.ConcurrentHashMap

object EmoteManager {
    private val TAG = EmoteManager::class.java.simpleName

    private const val BASE_URL = "https://static-cdn.jtvnw.net/emoticons/v1/"
    private const val EMOTE_SIZE = "3.0"
    private const val LOW_RES_EMOTE_SIZE = "2.0"

    private val twitchEmotes = ConcurrentHashMap<String, GenericEmote>()

    private val ffzEmotes = ConcurrentHashMap<String, HashMap<String, GenericEmote>>()
    private val globalFFZEmotes = ConcurrentHashMap<String, GenericEmote>()

    private const val BTTV_CDN_BASE_URL = "https://cdn.betterttv.net/emote/"
    private val bttvEmotes = ConcurrentHashMap<String, HashMap<String, GenericEmote>>()
    private val globalBttvEmotes = ConcurrentHashMap<String, GenericEmote>()

    private val ffzModBadges = ConcurrentHashMap<String, String>()
    private val channelBadges = ConcurrentHashMap<String, BadgeEntities.Result>()
    private val globalBadges = ConcurrentHashMap<String, BadgeEntities.BadgeSet>()

    private val thirdPartyRegex = Regex("\\s")
    private val emoteReplacements = mapOf(
        "[oO](_|\\.)[oO]" to "O_o",
        "\\&lt\\;3" to "<3",
        "\\:-?(p|P)" to ":P",
        "\\:-?[z|Z|\\|]" to ":Z",
        "\\:-?\\)" to ":)",
        "\\;-?(p|P)" to ";P",
        "R-?\\)" to "R)",
        "\\&gt\\;\\(" to ">(",
        "\\:-?(o|O)" to ":O",
        "\\:-?[\\\\/]" to ":/",
        "\\:-?\\(" to ":(",
        "\\:-?D" to ":D",
        "\\;-?\\)" to ";)",
        "B-?\\)" to "B)",
        "#-?[\\/]" to "#/",
        ":-?(?:7|L)" to ":7",
        "\\&lt\\;\\]" to "<]",
        "\\:-?(S|s)" to ":s",
        "\\:\\&gt\\;" to ":>"
    )

    val gifCache = LruCache<String, GifDrawable>(128)
    val gifCallback = MultiCallback(true)

    fun parseTwitchEmotes(emoteTag: String, original: String, spaces: List<Int>): List<ChatMessageEmote> {
        if (emoteTag.isEmpty()) {
            return emptyList()
        }

        // Characters with supplementary codepoints have two chars and need to be considered into emote positioning
        val supplementaryCodePointPositions = original.supplementaryCodePointPositions
        val emotes = arrayListOf<ChatMessageEmote>()
        for (emote in emoteTag.split('/')) {
            val split = emote.split(':')
            // bad emote data :)
            if (split.size != 2) continue

            val (id, positions) = split
            val pairs = positions.split(',')
            // bad emote data :)
            if (pairs.isEmpty()) continue

            // skip over invalid parsed data
            val parsedPositions = pairs.mapNotNull { pos ->
                val pair = pos.split('-')
                if (pair.size != 2) return@mapNotNull null

                val start = pair[0].toIntOrNull() ?: return@mapNotNull null
                val end = pair[1].toIntOrNull() ?: return@mapNotNull null
                start to end + 1
            }
            val fixedParsedPositions = parsedPositions.map { (start, end) ->
                val extra = supplementaryCodePointPositions.count { it < start }
                val spaceExtra = spaces.count { it < start + extra }
                return@map "${(start + extra + spaceExtra)}-${(end + extra + spaceExtra)}"
            }
            val code = original.substring(parsedPositions.first().first, parsedPositions.first().second)

            emotes += ChatMessageEmote(
                positions = fixedParsedPositions,
                url = "$BASE_URL/$id/$EMOTE_SIZE",
                id = id,
                code = code,
                scale = 1,
                isGif = false,
                isTwitch = true
            )
        }
        return emotes
    }

    fun parse3rdPartyEmotes(message: String, channel: String = ""): List<ChatMessageEmote> {
        val splits = message.split(thirdPartyRegex)
        val emotes = arrayListOf<ChatMessageEmote>()

        ffzEmotes[channel]?.forEach { parseMessageForEmote(it.value, splits, emotes) }
        bttvEmotes[channel]?.forEach { parseMessageForEmote(it.value, splits, emotes) }
        globalBttvEmotes.forEach { parseMessageForEmote(it.value, splits, emotes) }
        globalFFZEmotes.forEach { parseMessageForEmote(it.value, splits, emotes) }

        return emotes
    }

    fun getChannelBadgeUrl(channel: String, set: String, version: String) = channelBadges[channel]?.sets?.get(set)?.versions?.get(version)?.imageUrlHigh

    fun getGlobalBadgeUrl(set: String, version: String) = globalBadges[set]?.versions?.get(version)?.imageUrlHigh

    fun getFFzModBadgeUrl(channel: String) = ffzModBadges[channel]

    suspend fun setChannelBadges(channel: String, entity: BadgeEntities.Result) = withContext(Dispatchers.Default) {
        channelBadges[channel] = entity
    }

    suspend fun setGlobalBadges(entity: BadgeEntities.Result) = withContext(Dispatchers.Default) {
        globalBadges.putAll(entity.sets)
    }

    suspend fun setTwitchEmotes(twitchResult: EmoteEntities.Twitch.Result) = withContext(Dispatchers.Default) {
        val setMapping = twitchResult.sets.keys
            .map {
                async { TwitchApi.getUserSet(it) ?: EmoteEntities.Twitch.EmoteSet(it, "", "", 1) }
            }.awaitAll()
            .associateBy({ it.id }, { it.channelName })

        twitchEmotes.clear()
        twitchResult.sets.forEach {
            val type = when (val set = it.key) {
                "0", "42" -> EmoteType.GlobalTwitchEmote // 42 == monkey emote set, move them to the global emote section
                else -> EmoteType.ChannelTwitchEmote(setMapping[set] ?: "Twitch")
            }
            it.value.forEach { emoteResult ->
                val code = when (type) {
                    is EmoteType.GlobalTwitchEmote -> emoteReplacements[emoteResult.name] ?: emoteResult.name
                    else -> emoteResult.name
                }
                val emote = GenericEmote(
                    code = code,
                    url = "$BASE_URL/${emoteResult.id}/$EMOTE_SIZE",
                    lowResUrl = "$BASE_URL/${emoteResult.id}/$LOW_RES_EMOTE_SIZE",
                    isGif = false,
                    id = "${emoteResult.id}",
                    scale = 1,
                    emoteType = type
                )
                twitchEmotes[emote.code] = emote
            }
        }
    }

    suspend fun setFFZEmotes(channel: String, ffzResult: EmoteEntities.FFZ.Result) = withContext(Dispatchers.Default) {
        val emotes = hashMapOf<String, GenericEmote>()
        ffzResult.sets.forEach {
            it.value.emotes.forEach { emote ->
                val parsedEmote = parseFFZEmote(emote, channel)
                emotes[parsedEmote.code] = parsedEmote
            }
        }
        ffzEmotes[channel] = emotes
        ffzResult.room.moderatorBadgeUrl?.let { ffzModBadges[channel] = "https:$it" }
    }

    suspend fun setFFZGlobalEmotes(ffzResult: EmoteEntities.FFZ.GlobalResult) = withContext(Dispatchers.Default) {
        globalFFZEmotes.clear()
        ffzResult.sets.forEach {
            it.value.emotes.forEach { emote ->
                val parsedEmote = parseFFZEmote(emote)
                globalFFZEmotes[parsedEmote.code] = parsedEmote
            }
        }
    }

    suspend fun setBTTVEmotes(channel: String, bttvResult: EmoteEntities.BTTV.Result) = withContext(Dispatchers.Default) {
        val emotes = hashMapOf<String, GenericEmote>()
        bttvResult.emotes.plus(bttvResult.sharedEmotes).forEach {
            val emote = parseBTTVEmote(it)
            emotes[emote.code] = emote
        }
        bttvEmotes[channel] = emotes
    }

    suspend fun setBTTVGlobalEmotes(globalEmotes: List<EmoteEntities.BTTV.GlobalEmote>) = withContext(Dispatchers.Default) {
        globalBttvEmotes.clear()
        globalEmotes.forEach {
            val emote = parseBTTVGlobalEmote(it)
            globalBttvEmotes[emote.code] = emote
        }
    }

    suspend fun getEmotes(channel: String): List<GenericEmote> = withContext(Dispatchers.Default) {
        val result = mutableListOf<GenericEmote>()
        result.addAll(twitchEmotes.values)
        result.addAll(globalFFZEmotes.values)
        result.addAll(globalBttvEmotes.values)
        ffzEmotes[channel]?.let { result.addAll(it.values) }
        bttvEmotes[channel]?.let { result.addAll(it.values) }
        return@withContext result.sortedBy { it.code }
    }

    private fun parseMessageForEmote(emote: GenericEmote, messageSplits: List<String>, listToAdd: MutableList<ChatMessageEmote>) {
        var i = 0
        val positions = mutableListOf<String>()
        messageSplits.forEach { split ->
            if (emote.code == split.trim()) {
                positions += "$i-${i + split.length}"
            }
            i += split.length + 1
        }
        if (positions.size > 0) {
            listToAdd += ChatMessageEmote(
                positions = positions,
                url = emote.url,
                id = emote.id,
                code = emote.code,
                scale = emote.scale,
                isGif = emote.isGif
            )
        }
    }

    private fun parseBTTVEmote(emote: EmoteEntities.BTTV.Emote): GenericEmote {
        val name = emote.code
        val id = emote.id
        val type = emote.imageType == "gif"
        val url = "$BTTV_CDN_BASE_URL$id/3x"
        val lowResUrl = "$BTTV_CDN_BASE_URL$id/2x"
        return GenericEmote(name, url, lowResUrl, type, id, 1, EmoteType.ChannelBTTVEmote)
    }

    private fun parseBTTVGlobalEmote(emote: EmoteEntities.BTTV.GlobalEmote): GenericEmote {
        val name = emote.code
        val id = emote.id
        val type = emote.imageType == "gif"
        val url = "$BTTV_CDN_BASE_URL$id/3x"
        val lowResUrl = "$BTTV_CDN_BASE_URL$id/2x"
        return GenericEmote(name, url, lowResUrl, type, id, 1, EmoteType.GlobalBTTVEmote)
    }

    private fun parseFFZEmote(emote: EmoteEntities.FFZ.Emote, channel: String = ""): GenericEmote {
        val name = emote.name
        val id = emote.id
        val (scale, url) = when {
            emote.urls.containsKey("4") -> 1 to emote.urls.getValue("4")
            emote.urls.containsKey("2") -> 2 to emote.urls.getValue("2")
            else -> 4 to emote.urls.getValue("1")
        }
        val lowResUrl = emote.urls["2"] ?: emote.urls.getValue("1")
        val type = when {
            channel.isBlank() -> EmoteType.GlobalFFZEmote
            else -> EmoteType.ChannelFFZEmote
        }
        return GenericEmote(name, "https:$url", "https:$lowResUrl", false, "$id", scale, type)
    }
}