package lk.codegen.risime.calls

/**
 * §16.10 SDP rules (crypto R4–R6): strict validation before ringing (offer) or applying (answer,
 * restart), the audio-level header extension stripped from everything this app produces, the Opus
 * parameters of §16.9, and the DTLS fingerprint the call is pinned to.
 */
object SdpRules {
    enum class Role { OFFER, ANSWER }

    const val MAX_BYTES = 16 * 1024
    const val AUDIO_LEVEL = "urn:ietf:params:rtp-hdrext:ssrc-audio-level"
    const val PROTO = "UDP/TLS/RTP/SAVPF"

    /** §16.9 Opus: mono 48 kHz, ~32 kbit/s, in-band FEC, DTX, CBR (crypto S1). */
    val OPUS_PARAMS = linkedMapOf(
        "minptime" to "10",
        "useinbandfec" to "1",
        "usedtx" to "1",
        "cbr" to "1",
        "stereo" to "0",
        "maxaveragebitrate" to "32000",
    )
    const val PTIME = 20

    private val FINGERPRINT = Regex("^a=fingerprint:sha-256 ([0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){31})$")

    fun lines(sdp: String): List<String> = sdp.split("\r\n", "\n").filter { it.isNotEmpty() }

    /** null = valid; otherwise why it is dropped. */
    fun validate(sdp: String, role: Role): String? {
        if (sdp.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return "over 16 KiB"
        val ls = lines(sdp)
        if (ls.firstOrNull() != "v=0") return "no v=0"
        val mLines = ls.filter { it.startsWith("m=") }
        if (mLines.size != 1) return "${mLines.size} m-lines"
        val m = mLines.single().removePrefix("m=").split(' ')
        if (m.size < 4 || m[0] != "audio") return "not m=audio"
        if (m[2] != PROTO) return "proto ${m[2]}"
        val fps = ls.filter { it.startsWith("a=fingerprint:") }
        if (fps.size != 1) return "${fps.size} fingerprints"
        if (!FINGERPRINT.matches(fps.single())) return "fingerprint not sha-256 with 32 bytes"
        if (ls.any { it.startsWith("a=crypto:") }) return "a=crypto (SDES)"
        val setups = ls.filter { it.startsWith("a=setup:") }.map { it.removePrefix("a=setup:") }
        if (setups.isEmpty()) return "no a=setup"
        val okSetup = when (role) {
            Role.OFFER -> setups.all { it == "actpass" }
            Role.ANSWER -> setups.all { it == "active" || it == "passive" }
        }
        if (!okSetup) return "a=setup:${setups.joinToString(",")} in an ${role.name.lowercase()}"
        if (ls.none { it.startsWith("a=ice-ufrag:") && it.length > "a=ice-ufrag:".length }) return "no a=ice-ufrag"
        if (ls.none { it.startsWith("a=ice-pwd:") && it.length > "a=ice-pwd:".length }) return "no a=ice-pwd"
        if (ls.none { it == "a=rtcp-mux" }) return "no a=rtcp-mux"
        if (ls.any { it.startsWith("a=extmap:") && it.contains(AUDIO_LEVEL) }) return "audio-level header extension"
        if (opusPayloadType(ls) == null) return "no opus/48000/2"
        return null
    }

    private fun opusPayloadType(ls: List<String>): String? = ls.firstNotNullOfOrNull { l ->
        Regex("^a=rtpmap:(\\d+) opus/48000/2$", RegexOption.IGNORE_CASE).find(l)?.groupValues?.get(1)
    }

    /** The `a=fingerprint:sha-256` value, normalised (uppercase hex, no colons); null if absent. */
    fun fingerprint(sdp: String): String? = lines(sdp).firstNotNullOfOrNull { FINGERPRINT.find(it)?.groupValues?.get(1) }?.let(::normalize)

    /** Case-insensitive, colons ignored (§16.10 e). */
    fun normalize(fp: String): String = fp.replace(":", "").uppercase()

    fun sameFingerprint(a: String?, b: String?): Boolean = a != null && b != null && normalize(a) == normalize(b)

    /** crypto R5: drop every `a=extmap` naming the audio-level extension. */
    fun stripAudioLevel(sdp: String): String = rebuild(lines(sdp).filterNot { it.startsWith("a=extmap:") && it.contains(AUDIO_LEVEL) })

    /** §16.9: the Opus fmtp parameters and `a=ptime:20` on the Opus payload. */
    fun tuneOpus(sdp: String): String {
        val ls = lines(sdp).toMutableList()
        val pt = opusPayloadType(ls) ?: return sdp
        val fmtpIdx = ls.indexOfFirst { it.startsWith("a=fmtp:$pt ") }
        val params = LinkedHashMap<String, String>()
        if (fmtpIdx >= 0) {
            ls[fmtpIdx].substringAfter(' ').split(';').map { it.trim() }.filter { it.contains('=') }.forEach {
                params[it.substringBefore('=')] = it.substringAfter('=')
            }
        }
        params.putAll(OPUS_PARAMS)
        params.remove("sprop-stereo")
        val fmtp = "a=fmtp:$pt " + params.entries.joinToString(";") { "${it.key}=${it.value}" }
        if (fmtpIdx >= 0) {
            ls[fmtpIdx] = fmtp
        } else {
            val rtpmapIdx = ls.indexOfFirst { it.startsWith("a=rtpmap:$pt ") }
            ls.add(rtpmapIdx + 1, fmtp)
        }
        val ptimeIdx = ls.indexOfFirst { it.startsWith("a=ptime:") }
        if (ptimeIdx >= 0) ls[ptimeIdx] = "a=ptime:$PTIME" else ls.add(ls.indexOfFirst { it.startsWith("a=fmtp:$pt ") } + 1, "a=ptime:$PTIME")
        return rebuild(ls)
    }

    /** What this app produces: audio level stripped, Opus tuned. */
    fun prepareLocal(sdp: String): String = tuneOpus(stripAudioLevel(sdp))

    /**
     * §16.10 (f) the debug-only negative test: flip one byte of the fingerprint (before
     * `setRemoteDescription`). Never used outside the debug hook and tests.
     */
    fun tamperFingerprint(sdp: String): String = rebuild(
        lines(sdp).map { l ->
            val m = FINGERPRINT.find(l) ?: return@map l
            val v = m.groupValues[1]
            val first = v.substring(0, 2).toInt(16) xor 0x01
            "a=fingerprint:sha-256 " + "%02X".format(first) + v.substring(2)
        },
    )

    private fun rebuild(ls: List<String>): String = ls.joinToString("\r\n", postfix = "\r\n")
}
