package com.hitv

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * HiTV v1 — Super drama world HiTV (app 3.13.3, package com.thyra.nyctal),
 * converted to a CloudStream extension in the OLD repo at the user's order.
 *
 * Chain verified live 2026-10-31 on the app's own backend (no login):
 * - Browse: signed GET /home/h5/navigationBar + /home/h5/getHome
 *   (native client android_hitv1128 / versionCode 216) -> sections with
 *   numeric title ids (the `cid` slug is NOT the API id).
 * - Detail: signed GET /cms/web/movieDrama/get?category={0|1}&id={id}
 *   -> episodeVo[] (episode id + vid + totalTime).
 * - Play: signed GET /cms/web/ios_h5/movieDrama/getPlayInfo
 *   ?category=&id=&episodeId= -> FULL HLS mediaUrl on
 *   ali-cdn-play.loklok.cloud (verified: Blue Brick Man 103/44 = 433
 *   segments / 4325s; Colony 135949/1427672 = 7351s; One Room TA
 *   162849/1729662 category 1 = 606s).
 * - The OLD /cms/web/h5/.../getPlayInfo path is PREVIEW-ONLY
 *   (ali-cdn-preview-web, freeDuration 300, end=300) and is NOT used.
 * - Subtitles come from getPlayInfo subtitlingList (languageAbbr + URL).
 *
 * Known v1 limits, stated plainly, not hidden:
 * - Search endpoints are broken on this service version (searchDrama
 *   404, searchWithKeyWord B0001, searchLenovo zero results), so
 *   search() returns empty and browsing is home sections only.
 * - The API is burst-sensitive (A0201): calls are retried with 6s
 *   spacing, so home/detail/play can take seconds, not milliseconds.
 * - Signing (decoded from the app's own H5 JS, Nuxt 4.3.4):
 *   sign = MD5hex(Base64(AES-128-ECB( ts + Base64url(sorted param
 *   values concatenated) ))); aesKey = RSA/PKCS1-encrypted random
 *   16-char key with the public key shipped in that same JS.
 */
class HiTVProvider : MainAPI() {
    override var mainUrl = "https://h5-api.hitv.ltd"
    override var name = "HiTV"
    override var lang = "ar"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "1000004" to "الرئيسية",
        "1000016" to "دراما كورية",
        "1000017" to "أفلام",
        "1000018" to "أنمي",
        "1000019" to "مسلسلات أجنبية",
    )

    // ---------- signing ----------

    private companion object {
        const val API = "https://h5-api.hitv.ltd"
        const val PUB_B64 =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQC7GW1zgx9/ssgCjoZhuCvISy5Ns9T2UgAzjJqS2uTGuCVtZsN3TE5wd4OIeiVG2TVDH2Gxlzrxd5jg7P6IiUKqsliSdZxx/ceqLDawKgvO8mJ+hJJsuIxSL7Bi6T0p+xH6ibw4orGfCFUJhGryE9hqp9qTRiHOMvgC2si1VqrgaQIDAQAB"
        const val ALPH =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    }

    private val signKey: String =
        (1..16).map { ALPH.random() }.joinToString("")

    private val signAesKey: String by lazy {
        val keyBytes = Base64.getDecoder().decode(PUB_B64)
        val pub = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(keyBytes))
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pub)
        Base64.getEncoder().encodeToString(
            cipher.doFinal(signKey.toByteArray(Charsets.UTF_8))
        )
    }

    private fun md5Hex(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sign(params: Map<String, String>, ts: Long): String {
        val sortedValues = params.entries.sortedBy { it.key }
            .joinToString("") { it.value }
        val b64url = Base64.getEncoder()
            .encodeToString(sortedValues.toByteArray(Charsets.UTF_8))
            .replace("+", "-").replace("/", "_")
        val plain = ts.toString() + b64url
        val aes = Cipher.getInstance("AES/ECB/PKCS5Padding")
        aes.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(signKey.toByteArray(Charsets.UTF_8), "AES")
        )
        val ct = Base64.getEncoder().encodeToString(
            aes.doFinal(plain.toByteArray(Charsets.UTF_8))
        )
        return md5Hex(ct)
    }

    private fun signedHeaders(
        params: Map<String, String>,
        clientType: String,
        versionCode: String,
    ): Map<String, String> {
        val ts = System.currentTimeMillis()
        return mapOf(
            "currentTime" to ts.toString(),
            "sign" to sign(params, ts),
            "aesKey" to signAesKey,
            "lang" to "ar",
            "deviceid" to "cs-device-001",
            "clientType" to clientType,
            "versionCode" to versionCode,
            "timezone" to "GMT+2",
            "User-Agent" to "Mozilla/5.0",
            "Accept" to "application/json",
        )
    }

    /** Signed GET, retried on the burst-sensitive A0201 code. Returns data or null. */
    private suspend fun apiData(
        path: String,
        params: Map<String, String>,
        clientType: String,
        versionCode: String,
    ): JSONObject? {
        val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
        for (attempt in 0..2) {
            val text = runCatching {
                app.get("$API$path?$query", headers = signedHeaders(params, clientType, versionCode)).text
            }.getOrNull()
            if (text != null) {
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (json != null) {
                    if (json.optString("code") == "00000") {
                        return json.optJSONObject("data")
                    }
                    // Any non-success code: retry once more after a pause,
                    // the service is flaky under bursts (A0201 especially).
                    if (attempt < 2) Thread.sleep(6000) else return null
                } else if (attempt < 2) Thread.sleep(6000)
            } else if (attempt < 2) Thread.sleep(6000)
        }
        return null
    }

    // ---------- home ----------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        if (page != 1) return newHomePageResponse(emptyList(), false)
        val navId = request.data.toIntOrNull()
            ?: return newHomePageResponse(emptyList(), false)
        val data = apiData(
            "/home/h5/getHome",
            mapOf("page" to "1", "navigationId" to navId.toString()),
            "android_hitv1128", "216"
        ) ?: return newHomePageResponse(emptyList(), false)
        val lists = mutableListOf<HomePageList>()
        val sections = data.optJSONArray("recommendItems") ?: return newHomePageResponse(emptyList(), false)
        for (i in 0 until sections.length()) {
            val sec = sections.optJSONObject(i) ?: continue
            val items = sec.optJSONArray("recommendContentVOList") ?: continue
            if (items.length() == 0) continue
            val cards = mutableListOf<SearchResponse>()
            val seen = HashSet<Int>()
            for (j in 0 until items.length()) {
                val it = items.optJSONObject(j) ?: continue
                val id = it.optInt("id", 0)
                if (id == 0 || !seen.add(id)) continue
                val title = it.optString("title").ifBlank { it.optString("videoName") }
                if (title.isBlank()) continue
                val cat = it.optInt("category", 0)
                // Absolute URL: a custom "hitv:cat:id" scheme gets
                // normalised by CloudStream against mainUrl, which is
                // exactly what broke v1 detail pages on-device.
                val url = "https://hitv.local/$cat/$id"
                val poster = it.optString("imageUrl")
                val card = if (cat == 0) {
                    newMovieSearchResponse(title, url, TvType.Movie)
                } else {
                    newTvSeriesSearchResponse(title, url, TvType.TvSeries)
                }
                if (poster.isNotBlank()) card.posterUrl = poster
                cards += card
            }
            if (cards.isNotEmpty()) {
                val secName = sec.optString("homeSectionName")
                    .ifBlank { sec.optString("cnName") }
                    .ifBlank { request.name }
                lists += HomePageList(secName, cards)
            }
        }
        return newHomePageResponse(lists, false)
    }

    // ---------- search: broken on the service version, honestly empty ----------

    override suspend fun search(query: String): List<SearchResponse> {
        // Endpoints tried 2026-10-31: searchDrama -> 404, searchWithKeyWord
        // -> B0001, searchLenovo -> success with zero results. Browse the
        // home sections instead; do not fake results.
        return emptyList()
    }

    // ---------- detail ----------

    override suspend fun load(url: String): LoadResponse? {
        // Accept all three forms: v2 absolute "https://hitv.local/cat/id",
        // v1 "hitv:cat:id", and v1 as CloudStream normalised it on-device
        // ("https://h5-api.hitv.ltd/hitv:cat:id", which the old split(":")
        // parser rejected and turned into "Error loading").
        val m = Regex("""hitv:(\d+):(\d+)""").find(url)
            ?: Regex("""hitv\.local/(\d+)/(\d+)""").find(url)
            ?: return null
        val cat = m.groupValues[1].toIntOrNull() ?: return null
        val id = m.groupValues[2].toIntOrNull() ?: return null
        val data = apiData(
            "/cms/web/movieDrama/get",
            mapOf("category" to cat.toString(), "id" to id.toString()),
            "H5", "32"
        ) ?: return null
        val title = data.optString("name").ifBlank { return null }
        val poster = data.optString("coverVerticalUrl")
            .ifBlank { data.optString("coverHorizontalUrl") }
        val plot = data.optString("introduction")
        val year = data.optInt("year", 0).takeIf { it > 0 }
        val tags = mutableListOf<String>()
        val tagArr = data.optJSONArray("tagNameList")
        if (tagArr != null) {
            for (i in 0 until tagArr.length()) {
                tagArr.optString(i).takeIf { it.isNotBlank() }?.let { tags += it }
            }
        }
        val eps = data.optJSONArray("episodeVo") ?: return null
        if (eps.length() == 0) return null
        if (cat == 0) {
            val ep = eps.optJSONObject(0) ?: return null
            val epId = ep.optInt("id", 0)
            if (epId == 0) return null
            return newMovieLoadResponse(title, url, TvType.Movie, "hitvPlay:$cat:$id:$epId") {
                if (poster.isNotBlank()) this.posterUrl = poster
                if (plot.isNotBlank()) this.plot = plot
                if (year != null) this.year = year
                if (tags.isNotEmpty()) this.tags = tags
            }
        }
        val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
        for (i in 0 until eps.length()) {
            val ep = eps.optJSONObject(i) ?: continue
            val epId = ep.optInt("id", 0)
            if (epId == 0) continue
            val seriesNo = ep.optInt("seriesNo", 0)
            val epNum = if (seriesNo > 0) seriesNo else i + 1
            val epName = ep.optString("name").ifBlank { "الحلقة $epNum" }
            episodes += newEpisode("hitvPlay:$cat:$id:$epId") {
                this.season = 1
                this.episode = epNum
                this.name = epName
            }
        }
        if (episodes.isEmpty()) return null
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            if (poster.isNotBlank()) this.posterUrl = poster
            if (plot.isNotBlank()) this.plot = plot
            if (year != null) this.year = year
            if (tags.isNotEmpty()) this.tags = tags
        }
    }

    // ---------- play ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val pm = Regex("""hitvPlay:(\d+):(\d+):(\d+)""").find(data)
        if (pm == null) return false
        val cat = pm.groupValues[1]
        val id = pm.groupValues[2]
        val epId = pm.groupValues[3]
        val play = apiData(
            "/cms/web/ios_h5/movieDrama/getPlayInfo",
            mapOf("category" to cat, "id" to id, "episodeId" to epId),
            "H5", "32"
        )
        if (play == null) return false
        val mediaUrl = play.optString("mediaUrl")
        if (mediaUrl.isBlank()) return false
        val subs = play.optJSONArray("subtitlingList")
        if (subs != null) {
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                val surl = s.optString("subtitlingUrl")
                if (surl.isBlank()) continue
                val slab = s.optString("language").ifBlank { s.optString("languageAbbr") }
                subtitleCallback(SubtitleFile(slab.ifBlank { "Subtitle" }, surl))
            }
        }
        callback(
            newExtractorLink(
                "HiTV",
                "HiTV",
                mediaUrl,
                ExtractorLinkType.M3U8
            ) {
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
