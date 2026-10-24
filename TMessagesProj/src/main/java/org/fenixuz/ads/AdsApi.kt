package org.fenixuz.ads

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.telegram.messenger.BuildConfig
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import java.net.Inet4Address
import java.util.concurrent.TimeUnit

/**
 * Novagram: client for the ads backend's serving API v2 (contract: the backend team's NOVAGRAM_ADS_API_UZ.md,
 * 2026-10-21). One endpoint picks the ad for a slot ([AdsApiService.serve], free), one takes view/click/
 * action/video_open in batches ([AdsApiService.events], a `view` is what bills the advertiser); the system
 * banners above the chat list have their own three ([AdsApiService.bannersActive] and the rest).
 *
 * Every model is @Keep with explicit @SerializedName: they are Gson-mapped, and the event queue is also
 * persisted as JSON, so R8 must not rename a single field (see the Gson keep rule in proguard-rules.pro).
 * Response fields are all nullable: Gson fills them reflectively, so a missing field must not become a
 * "non-null" Kotlin value that is secretly null.
 */

@Keep
data class AdsServeRequest(
    @SerializedName("placement") val placement: String,
    @SerializedName("user_id") val userId: Long,
    @SerializedName("context") val context: Map<String, Any>,
    // Null fields are left out of the JSON (Gson's default); the backend rejects an empty phone_country
    // with 400, so "unknown" must be absent, never "".
    @SerializedName("phone_country") val phoneCountry: String?,
    @SerializedName("lang") val lang: String?,
    @SerializedName("platform") val platform: String,
    @SerializedName("app_version") val appVersion: String?
)

@Keep
data class AdsServeResponse(
    @SerializedName("ads") val ads: List<ServedAd>? = null,
    @SerializedName("ttl_seconds") val ttlSeconds: Int? = null,
    // VIDEO slot only, and missing when the backend is degraded.
    @SerializedName("start_delay") val startDelay: Int? = null,
    @SerializedName("between_delay") val betweenDelay: Int? = null
)

@Keep
data class ServedAd(
    // Signed impression token (~104 chars, lives 1 h, bound to the user_id it was served to). Opaque.
    @SerializedName("random_id") val randomId: String? = null,
    // The ad FORMAT (SEARCH, CHANNEL_MESSAGE, USERS_CHANNEL_MESSAGE, ...). Draw by slot, not by this.
    @SerializedName("placement") val placement: String? = null,
    @SerializedName("text") val text: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("promoted") val promoted: AdPromoted? = null,
    @SerializedName("button_type") val buttonType: String? = null,
    @SerializedName("media") val media: AdMedia? = null,
    @SerializedName("other_info") val otherInfo: String? = null,
    @SerializedName("label") val label: String? = null,
    @SerializedName("min_display_duration") val minDisplayDuration: Int? = null,
    @SerializedName("max_display_duration") val maxDisplayDuration: Int? = null
)

@Keep
data class AdPromoted(
    @SerializedName("username") val username: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("avatar_url") val avatarUrl: String? = null,
    // Bot-API form (-100…) for channels; always null for bots.
    @SerializedName("telegram_id") val telegramId: Long? = null,
    // "CHANNEL" or "BOT".
    @SerializedName("type") val type: String? = null
)

@Keep
data class AdMedia(
    @SerializedName("type") val type: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("width") val width: Int? = null,
    @SerializedName("height") val height: Int? = null,
    @SerializedName("duration") val duration: String? = null
)

@Keep
data class AdsEventsRequest(
    @SerializedName("user_id") val userId: Long,
    @SerializedName("events") val events: List<AdsEventItem>
)

@Keep
data class AdsEventItem(
    @SerializedName("random_id") val randomId: String,
    @SerializedName("type") val type: String
)

@Keep
data class AdsEventsResponse(
    @SerializedName("accepted") val accepted: Int? = null,
    @SerializedName("rejected") val rejected: Int? = null
)

@Keep
data class AdsReportRequest(
    @SerializedName("user_id") val userId: Long,
    @SerializedName("random_id") val randomId: String,
    // spam, violence, pornography, illegal, misleading, other
    @SerializedName("reason") val reason: String
)

// ---- system banners: admin announcements, not ads ----

@Keep
data class BannerViewerRequest(
    // The Telegram user id as a decimal string, never hashed — the backend's decision, so a dismissed banner stays
    // dismissed across reinstalls and devices. Must never change form, or dismissed banners come back.
    @SerializedName("viewer_id") val viewerId: String
)

@Keep
data class BannerDismissRequest(
    @SerializedName("announcement_id") val announcementId: String,
    @SerializedName("viewer_id") val viewerId: String
)

@Keep
data class BannerClickRequest(
    @SerializedName("announcement_id") val announcementId: String
)

@Keep
data class SystemBanner(
    // UUID; sent back as announcement_id.
    @SerializedName("id") val id: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("text") val text: String? = null,
    // https://…, tg://… or "" (not clickable).
    @SerializedName("link") val link: String? = null,
    // false: no X (important notice).
    @SerializedName("dismissible") val dismissible: Boolean? = null,
    // ISO-8601 with offset (fractional seconds possible), or null for no end.
    @SerializedName("ends_at") val endsAt: String? = null
)

// The paths come from local.properties (ADS_PATH_*) like the base URL and the key, so the public repo names no
// endpoint. Each keeps its trailing slash: without it the request lands on another service and fails.
interface AdsApiService {
    @POST(BuildConfig.ADS_PATH_SERVE)
    fun serve(@Body body: AdsServeRequest): Call<AdsServeResponse>

    @POST(BuildConfig.ADS_PATH_EVENTS)
    fun events(@Body body: AdsEventsRequest): Call<AdsEventsResponse>

    // 201 = reported, 200 = this viewer already reported this ad; the body is not needed.
    @POST(BuildConfig.ADS_PATH_REPORT)
    fun report(@Body body: AdsReportRequest): Call<Void>

    // A bare JSON array, most important first. An array type rather than a List keeps the method's generic
    // signature to Call<T> alone, like every other method here.
    @POST(BuildConfig.ADS_PATH_BANNERS_ACTIVE)
    fun bannersActive(@Body body: BannerViewerRequest): Call<Array<SystemBanner>>

    @POST(BuildConfig.ADS_PATH_BANNERS_DISMISS)
    fun bannerDismiss(@Body body: BannerDismissRequest): Call<Void>

    @POST(BuildConfig.ADS_PATH_BANNERS_CLICK)
    fun bannerClick(@Body body: BannerClickRequest): Call<Void>
}

object AdsRetrofitClient {
    // Base URL + API key come from local.properties (the URL through BuildConfig, the key through [AdsKey]), so
    // neither the ads endpoint nor the key lives in the public source tree. Blank when unset — [enabled] is
    // then false and no request is ever made (Retrofit is never built with a blank baseUrl, which would throw).
    private val BASE_URL: String = BuildConfig.ADS_BASE_URL
    private val API_KEY: String = AdsKey.apiKey()

    // All must be present: every endpoint answers 403 without X-API-Key, so without a key the feature would only
    // hammer the backend; and Retrofit throws on a method whose path is empty — disable the feature instead.
    val enabled: Boolean get() = BASE_URL.isNotBlank() && API_KEY.isNotBlank() && PATHS_SET

    private val PATHS_SET: Boolean = listOf(
        BuildConfig.ADS_PATH_SERVE, BuildConfig.ADS_PATH_EVENTS, BuildConfig.ADS_PATH_REPORT,
        BuildConfig.ADS_PATH_BANNERS_ACTIVE, BuildConfig.ADS_PATH_BANNERS_DISMISS, BuildConfig.ADS_PATH_BANNERS_CLICK
    ).all { it.isNotBlank() }

    private val baseClient: OkHttpClient by lazy {
        // X-API-Key on every request. Redirects are off so the key can never follow a redirect to another
        // host — the backend has no legitimate redirects (an http→https one would drop the POST body anyway).
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("X-API-Key", API_KEY).build())
            }
            // IPv4 addresses first. On networks with a broken IPv6 path (verified on the office Wi-Fi, same as
            // the Firestore case in AnalyticsRemote) OkHttp 3 tries the IPv6 address first, stalls, and the 5 s
            // serve budget runs out before it ever falls back — so no ad was ever shown there.
            .dns { hostname -> Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 } }
            // The backend speaks HTTP/2, so every call shares one connection, and cancelling a call does not close it.
            // When a network silently drops an idle connection (seen on the office Wi-Fi after ~4 minutes), every
            // later call hung on that dead connection until its timeout. Pings find a dead connection within ~20 s
            // and close it; idle connections are not kept past 30 s.
            .pingInterval(10, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(2, 30, TimeUnit.SECONDS))
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    // For the ads' media files themselves (a HEAD for a video's size). No X-API-Key: media may move to another host,
    // and the key must never follow it there. Same IPv4-first lookup as baseClient, redirects allowed.
    val mediaClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns { hostname -> Dns.SYSTEM.lookup(hostname).sortedBy { if (it is Inet4Address) 0 else 1 } }
            .connectTimeout(3, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    // serve: 3 s connect / 5 s total — an ad that arrives late is better not shown at all.
    // events: 10 s. Both share baseClient's connection pool (newBuilder), so keep-alive is reused.
    val serveService: AdsApiService by lazy {
        create(baseClient.newBuilder().connectTimeout(3, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS).build())
    }

    val eventsService: AdsApiService by lazy {
        create(baseClient.newBuilder().connectTimeout(10, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build())
    }

    private fun create(client: OkHttpClient): AdsApiService {
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AdsApiService::class.java)
    }
}
