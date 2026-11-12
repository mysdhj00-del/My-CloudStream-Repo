package com.goojara

/**
 * Direct packed-JS video extraction (Goojara v1, ported from the
 * Wecima2 CxUnpack pattern).
 *
 * Why this exists: CloudStream's built-in loadExtractor returns
 * true when an extractor merely MATCHES a URL and runs without
 * throwing — even when it emits zero links. Some of Goojara's
 * direct-link hosts hide a real video URL in packed JS that the
 * stock extractor does not unpack on the phone. So the provider
 * unpacks the embed pages itself and emits the video URL
 * directly; loadExtractor stays only as a fallback for hosts
 * this cannot handle.
 *
 * Pure Kotlin stdlib, no Android or CloudStream calls.
 */
object GoojaraUnpack {

    private val packerRegex = Regex(
        """\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val videoUrlRegex = Regex(
        """https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*"""
    )

    /** Unpacks a P.A.C.K.E.R. block found in [html], or null. */
    fun unpackPackedJs(html: String): String? {
        val m = packerRegex.find(html) ?: return null
        val (payload, radixStr, countStr, dictStr) = m.destructured
        val radix = radixStr.toIntOrNull() ?: return null
        val count = countStr.toIntOrNull() ?: return null
        if (radix < 2 || radix > 36 || count <= 0) return null
        val dict = dictStr.split('|')
        if (dict.size < count) return null
        var out = payload
        for (i in count - 1 downTo 0) {
            val word = dict[i]
            if (word.isEmpty()) continue
            val key = toBase(i, radix)
            out = Regex("\\b$key\\b").replace(out) { word }
        }
        return out
    }

    /** First playable video URL (.m3u8 preferred, else .mp4) in [text]. */
    fun extractVideoUrl(text: String): String? {
        val urls = videoUrlRegex.findAll(text).map { it.value }.toList()
        return urls.firstOrNull { it.contains(".m3u8") } ?: urls.firstOrNull()
    }

    private fun toBase(n: Int, base: Int): String {
        if (n == 0) return "0"
        val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
        var v = n
        val sb = StringBuilder()
        while (v > 0) {
            sb.append(digits[v % base])
            v /= base
        }
        return sb.reverse().toString()
    }
}
