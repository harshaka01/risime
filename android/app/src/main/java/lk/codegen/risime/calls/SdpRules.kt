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
    const val CSRC_AUDIO_LEVEL = "urn:ietf:params:rtp-hdrext:csrc-audio-level"
    const val ABS_CAPTURE_TIME = "http://www.webrtc.org/experiments/rtp-hdrext/abs-capture-time"
    const val VIDEO_ORIENTATION = "urn:3gpp:video-orientation"

    /** §19.4 (crypto K3): stripped from every SDP this app produces; a video SDP carrying any is rejected. */
    val DENIED_EXTENSIONS = listOf(AUDIO_LEVEL, CSRC_AUDIO_LEVEL, ABS_CAPTURE_TIME, VIDEO_ORIENTATION)

    /** §19.4: `b=AS` on the video m-line, if present, at most this (kbit/s). */
    const val MAX_VIDEO_AS = 1500
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

    /** The number of m-lines (a 1:1 SDP: 1 = voice, 2 = video, §19.4). */
    fun mLineCount(sdp: String): Int = lines(sdp).count { it.startsWith("m=") }

    /** An answer whose `m=video` port is 0: video rejected, an audio call (§19.4). */
    fun videoRejected(sdp: String): Boolean = lines(sdp).firstOrNull { it.startsWith("m=video ") }?.split(' ')?.getOrNull(1) == "0"

    /** null = valid; otherwise why it is dropped. [video]: the §19.4 rules (one m=audio, one m=video, BUNDLE, VP8). */
    fun validate(sdp: String, role: Role, video: Boolean): String? = if (video) validateVideo(sdp, role) else validate(sdp, role)

    private class Section(val kind: String, val port: String, val proto: String, val pts: List<String>, val lines: List<String>) {
        val mid: String? get() = lines.firstOrNull { it.startsWith("a=mid:") }?.removePrefix("a=mid:")
    }

    private fun sections(ls: List<String>): Pair<List<String>, List<Section>> {
        val session = mutableListOf<String>()
        val out = mutableListOf<Section>()
        var cur: MutableList<String>? = null
        var head: List<String> = emptyList()
        fun close() {
            val c = cur ?: return
            out += Section(head.getOrElse(0) { "" }, head.getOrElse(1) { "" }, head.getOrElse(2) { "" }, head.drop(3), c)
        }
        for (l in ls) {
            if (l.startsWith("m=")) {
                close()
                head = l.removePrefix("m=").split(' ')
                cur = mutableListOf()
            } else {
                cur?.add(l) ?: session.add(l)
            }
        }
        close()
        return session to out
    }

    /** §19.4 (crypto K1–K5): every §16.10 rule, with the video replacements. */
    private fun validateVideo(sdp: String, role: Role): String? {
        if (sdp.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return "over 16 KiB"
        val ls = lines(sdp)
        if (ls.firstOrNull() != "v=0") return "no v=0"
        val (session, secs) = sections(ls)
        if (secs.size != 2) return "${secs.size} m-lines"
        val (audio, video) = secs[0] to secs[1]
        if (audio.kind != "audio" || video.kind != "video") return "not m=audio then m=video"
        if (audio.proto != PROTO || video.proto != PROTO) return "proto ${audio.proto}/${video.proto}"
        val rejected = video.port == "0"
        if (rejected && role == Role.OFFER) return "video port 0 in an offer"
        // K1: at least one fingerprint; every one sha-256 with 32 bytes, all the same value.
        val fps = ls.filter { it.startsWith("a=fingerprint:") }
        if (fps.isEmpty()) return "no fingerprint"
        if (fps.any { !FINGERPRINT.matches(it) }) return "fingerprint not sha-256 with 32 bytes"
        if (fps.map { normalize(FINGERPRINT.find(it)!!.groupValues[1]) }.toSet().size != 1) return "different fingerprints"
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
        // K2: BUNDLE naming both mids, rtcp-mux in each m-section (one DTLS transport).
        val bundle = session.firstOrNull { it.startsWith("a=group:BUNDLE") }?.removePrefix("a=group:BUNDLE")?.trim()?.split(' ')?.filter { it.isNotEmpty() }
            ?: return "no BUNDLE"
        val active = if (rejected) listOf(audio) else listOf(audio, video)
        if (active.any { s -> s.mid == null || s.mid !in bundle }) return "BUNDLE doesn't name every m-line"
        if (active.any { s -> s.lines.none { it == "a=rtcp-mux" } }) return "no a=rtcp-mux in every m-line"
        // K3: the leaking header extensions.
        if (ls.any { l -> l.startsWith("a=extmap:") && DENIED_EXTENSIONS.any { l.contains(it) } }) return "denied header extension"
        // K5: no simulcast.
        if (ls.any { it.startsWith("a=simulcast") }) return "a=simulcast"
        if (ls.any { it.startsWith("a=rid:") }) return "a=rid"
        val groups = ls.filter { it.startsWith("a=ssrc-group:") }
        if (groups.size > 1 || groups.any { !it.startsWith("a=ssrc-group:FID ") }) return "ssrc-group other than one FID"
        if (opusPayloadType(audio.lines) == null) return "no opus/48000/2"
        if (!rejected) {
            val rtpmap = video.lines.mapNotNull { l -> Regex("^a=rtpmap:(\\d+) ([^/]+)/(\\d+)").find(l)?.groupValues?.let { it[1] to it[2] } }.toMap()
            val vp8 = rtpmap.filterValues { it.equals("VP8", true) }.keys
            if (vp8.isEmpty() || video.lines.none { Regex("^a=rtpmap:\\d+ VP8/90000$", RegexOption.IGNORE_CASE).matches(it) }) return "no VP8/90000"
            if (role == Role.ANSWER) {
                // K4: an answer's video m-line lists only VP8 and its rtx.
                for (pt in video.pts) {
                    val name = rtpmap[pt] ?: return "payload $pt without rtpmap"
                    when {
                        name.equals("VP8", true) -> Unit
                        name.equals("rtx", true) -> {
                            val apt = video.lines.firstOrNull { it.startsWith("a=fmtp:$pt ") }?.let { Regex("apt=(\\d+)").find(it)?.groupValues?.get(1) }
                            if (apt == null || apt !in vp8) return "rtx not for VP8"
                        }
                        else -> return "video codec $name in an answer"
                    }
                }
            }
            video.lines.firstOrNull { it.startsWith("b=AS:") }?.removePrefix("b=AS:")?.toIntOrNull()?.let { if (it > MAX_VIDEO_AS) return "b=AS:$it" }
            if (video.lines.any { it.startsWith("b=AS:") && it.removePrefix("b=AS:").toIntOrNull() == null }) return "bad b=AS"
        }
        val dirs = listOf("a=sendrecv", "a=recvonly", "a=sendonly", "a=inactive")
        for (s in active) {
            val d = s.lines.filter { it in dirs }
            when (role) {
                Role.OFFER -> if (d != listOf("a=sendrecv")) return "${s.kind} not sendrecv in an offer"
                Role.ANSWER -> if (d.size > 1) return "${s.kind} has ${d.size} directions"
            }
        }
        return null
    }

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

    /** crypto R5 / §19.4 K3: drop every `a=extmap` naming a denied extension (voice too). */
    fun stripAudioLevel(sdp: String): String = rebuild(lines(sdp).filterNot { l -> l.startsWith("a=extmap:") && DENIED_EXTENSIONS.any { l.contains(it) } })

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
