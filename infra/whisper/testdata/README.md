# Speech-service test clips (internal testing only)

Mono 16 kHz Opus (24 kbit/s, `-application voip`, like call audio), re-encoded from the sources
below with ffmpeg. Used by `scripts/whisper-smoke`, `scripts/asr-smoke` and (the Sinhala clip)
`scripts/call-transcribe-test`.

**Internal testing only (Harsha, 2026-10-12).** These clips never go into an APK, a Docker image
or a release/publish mirror. `scripts/check-apk-testdata` (run by `scripts/nightly-release` and
`scripts/publish-release`) fails a build that contains any of them, or any audio file.

| File | Source | Licence (verified 2026-10-12) |
|---|---|---|
| `en-jfk.ogg` | J. F. Kennedy, inaugural address, 20 Jan 1961 ("And so, my fellow Americans, ask not what your country can do for you, ask what you can do for your country."), the `samples/jfk.wav` clip of whisper.cpp (https://github.com/ggml-org/whisper.cpp) | Public domain: a work of the US federal government (the President's official address) |
| `si-slr30-sin_6897_3117231257.ogg`, `si.txt` | OpenSLR SLR30 "High quality TTS data for Sinhala" (Google), utterance `sin_6897_3117231257`, https://www.openslr.org/30/ | CC BY-SA 4.0 (as stated on the OpenSLR page) |
| `ta-fleurs-10015420708072669120.ogg`, `ta.txt` | FLEURS (Google), `ta_in` test split, `10015420708072669120.wav`, https://huggingface.co/datasets/google/fleurs | CC BY 4.0 (the dataset card's `license: cc-by-4.0`) |

The si and ta clips are read speech by native speakers (not synthetic). The re-encoded clips and
reference texts keep the licences of their sources (attribution as in the table; the SLR30 clip
and `si.txt` are shared alike under CC BY-SA 4.0).

The synthetic MMS-TTS clips of the call-transcription gate live in `android/testdata/transcribe/`
(CC BY-NC 4.0 models, internal testing only; see that README).

**Next round:** real Sinhala, Tamil and English conversation clips will be recorded in-house with
a colleague (consented, for internal testing only) to replace or extend these read-speech clips.
