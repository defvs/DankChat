package com.flxrs.dankchat.service.api

import android.util.Log
import com.flxrs.dankchat.BuildConfig
import com.flxrs.dankchat.service.api.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.File
import java.net.URLConnection

object TwitchApi {
    private val TAG = TwitchApi::class.java.simpleName

    private const val KRAKEN_BASE_URL = "https://api.twitch.tv/kraken/"
    private const val HELIX_BASE_URL = "https://api.twitch.tv/helix/"
    private const val VALIDATE_URL = "https://id.twitch.tv/oauth2/validate"

    private const val TWITCH_SUBBADGES_BASE_URL = "https://badges.twitch.tv/v1/badges/channels/"
    private const val TWITCH_SUBBADGES_SUFFIX = "/display"
    private const val TWITCH_BADGES_URL = "https://badges.twitch.tv/v1/badges/global/display"

    private const val FFZ_BASE_URL = "https://api.frankerfacez.com/v1/room/id/"
    private const val FFZ_GLOBAL_URL = "https://api.frankerfacez.com/v1/set/global"

    private const val BTTV_CHANNEL_BASE_URL = "https://api.betterttv.net/3/cached/users/twitch/"
    private const val BTTV_GLOBAL_URL = "https://api.betterttv.net/3/cached/emotes/global"

    private const val RECENT_MSG_URL = "https://recent-messages.robotty.de/api/v2/recent-messages/"

    private const val NUULS_UPLOAD_URL = "https://i.nuuls.com/upload"

    private const val TWITCHEMOTES_SETS_URL = "https://api.twitchemotes.com/api/v4/sets?id="

    private const val BASE_LOGIN_URL = "https://id.twitch.tv/oauth2/authorize?response_type=token"
    private const val REDIRECT_URL = "https://flxrs.com/dankchat"
    private const val SCOPES = "chat:edit+chat:read+user_read+user_subscriptions" +
            "+channel:moderate+user_blocks_read+user_blocks_edit+whispers:read+whispers:edit" +
            "+channel_editor"
    const val CLIENT_ID = "xu7vd1i6tlr0ak45q1li2wdc0lrma8"
    const val LOGIN_URL = "$BASE_LOGIN_URL&client_id=$CLIENT_ID&redirect_uri=$REDIRECT_URL&scope=$SCOPES"

    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val request = chain.request()
            try {
                chain.proceed(request)
            } catch (e: IllegalArgumentException) {
                val new = request.newBuilder().cacheControl(CacheControl.FORCE_NETWORK).build()
                chain.proceed(new)
            }
        }
        .build()

    private val service = Retrofit.Builder()
        .baseUrl(KRAKEN_BASE_URL)
        .addConverterFactory(MoshiConverterFactory.create())
        .client(client)
        .build()
        .create(TwitchApiService::class.java)

    private val loadedRecentsInChannels = mutableListOf<String>()

    suspend fun validateUser(oAuth: String): UserEntities.ValidateUser? = withContext(Dispatchers.IO) {
        try {
            val response = service.validateUser(VALIDATE_URL, "OAuth $oAuth")
            if (response.isSuccessful) return@withContext response.body()
        } catch (t: Throwable) {
            Log.e(TAG, Log.getStackTraceString(t))
        }
        return@withContext null
    }

    suspend fun getUserEmotes(oAuth: String, id: Int): EmoteEntities.Twitch.Result? = withContext(Dispatchers.IO) {
        val response = service.getUserEmotes("OAuth $oAuth", id)
        response.bodyOrNull
    }

    suspend fun getUserSets(sets: List<String>): List<EmoteEntities.Twitch.EmoteSet>? = withContext(Dispatchers.IO) {
        val ids = sets.joinToString(",")
        val response = service.getSets("${TWITCHEMOTES_SETS_URL}$ids")
        response.bodyOrNull
    }

    suspend fun getUserSet(set: String): EmoteEntities.Twitch.EmoteSet? = withContext(Dispatchers.IO) {
        val response = service.getSet("https://flxrs.com/api/set/$set")
        response.bodyOrNull?.firstOrNull()
    }

    suspend fun getStream(oAuth: String, channel: String): StreamEntities.Stream? = withContext(Dispatchers.IO) {
        return@withContext getUserIdFromName(oAuth, channel)?.let {
            val response = service.getStream(it.toInt())
            response.bodyOrNull?.stream
        }
    }

    suspend fun getChannelBadges(id: String): BadgeEntities.Result? = withContext(Dispatchers.IO) {
        val response = service.getBadgeSets("$TWITCH_SUBBADGES_BASE_URL$id$TWITCH_SUBBADGES_SUFFIX")
        response.bodyOrNull
    }

    suspend fun getGlobalBadges(): BadgeEntities.Result? = withContext(Dispatchers.IO) {
        val response = service.getBadgeSets(TWITCH_BADGES_URL)
        response.bodyOrNull
    }

    suspend fun getFFZChannelEmotes(id: String): EmoteEntities.FFZ.Result? = withContext(Dispatchers.IO) {
        val response = service.getFFZChannelEmotes("$FFZ_BASE_URL$id")
        response.bodyOrNull
    }

    suspend fun getFFZGlobalEmotes(): EmoteEntities.FFZ.GlobalResult? = withContext(Dispatchers.IO) {
        val response = service.getFFZGlobalEmotes(FFZ_GLOBAL_URL)
        response.bodyOrNull
    }

    suspend fun getBTTVChannelEmotes(id: String): EmoteEntities.BTTV.Result? = withContext(Dispatchers.IO) {
        val response = service.getBTTVChannelEmotes("$BTTV_CHANNEL_BASE_URL$id")
        response.bodyOrNull
    }

    suspend fun getBTTVGlobalEmotes(): List<EmoteEntities.BTTV.GlobalEmote>? = withContext(Dispatchers.IO) {
        val response = service.getBTTVGlobalEmotes(BTTV_GLOBAL_URL)
        response.bodyOrNull
    }

    suspend fun getRecentMessages(channel: String): RecentMessages? = withContext(Dispatchers.IO) {
        when {
            loadedRecentsInChannels.contains(channel) -> null
            else -> {
                val response = service.getRecentMessages("$RECENT_MSG_URL$channel")
                response.bodyOrNull?.also { loadedRecentsInChannels.add(channel) }
            }
        }
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    suspend fun uploadMedia(file: File): String? = withContext(Dispatchers.IO) {
        val extension = file.extension.ifBlank { "png" }
        val mimetype = URLConnection.guessContentTypeFromName(file.name)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("abc", "abc.$extension", file.asRequestBody(mimetype.toMediaType()))
            .build()
        val request = Request.Builder()
            .url(NUULS_UPLOAD_URL)
            .header("User-Agent", "dankchat/${BuildConfig.VERSION_NAME}")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        when {
            response.isSuccessful -> response.body?.string()
            else -> null
        }
    }

    suspend fun getUserIdFromName(oAuth: String, name: String): String? = withContext(Dispatchers.IO) {
        val response = service.getUserHelix("Bearer $oAuth", "${HELIX_BASE_URL}users?login=$name")
        response.bodyOrNull?.data?.getOrNull(0)?.id
    }

    suspend fun getNameFromUserId(oAuth: String, id: Int): String? = withContext(Dispatchers.IO) {
        val response = service.getUserHelix("Bearer $oAuth", "${HELIX_BASE_URL}users?id=$id")
        response.bodyOrNull?.data?.getOrNull(0)?.name
    }

    suspend fun getIgnores(oAuth: String, id: Int): UserEntities.KrakenUsersBlocks? = withContext(Dispatchers.IO) {
        val response = service.getIgnores("OAuth $oAuth", id)
        response.bodyOrNull
    }

    fun clearChannelFromLoaded(channel: String) {
        loadedRecentsInChannels.remove(channel)
    }
}

private val <T> Response<T>.bodyOrNull
    get() = when {
        isSuccessful -> body()
        else -> null
    }
