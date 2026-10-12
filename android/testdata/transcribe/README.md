# Call-transcription gate clips (scripts/call-transcribe-test), internal testing only

Mono 16 kHz Ogg/Opus, 24 kbit/s. Injected in place of the microphone by the debug-only
`MIC_INJECT` seam (debug builds only), never played by a person. The gate copies them to the test
device at run time; they are **not** in `app/src/` and never packaged.

**Internal testing only (Harsha, 2026-10-12).** These clips never go into an APK or anything
published. `scripts/check-apk-testdata` (run by `scripts/nightly-release` and
`scripts/publish-release`) fails a build that contains any of them, or any audio file. Verified
2026-10-12 on the debug and release APKs built from `main` and on every APK in
`~/risime-releases/` and the publish mirror: no audio, no test clip.

| File | Source | Licence (verified 2026-10-12) |
|---|---|---|
| `en-tts.ogg` | Synthetic: MMS-TTS `facebook/mms-tts-eng` (VITS), text "Let's meet at the office tomorrow at ten. We will send the revised quote on Friday." | Made with a CC BY-NC 4.0 model: **internal testing only** |
| `ta-tts.ogg` | Synthetic: MMS-TTS `facebook/mms-tts-tam`, text "நாளை காலை பத்து மணிக்கு அலுவலகத்தில் சந்திப்போம். திருத்திய விலைப்பட்டியலை வெள்ளிக்கிழமை அனுப்புவோம்." | Made with a CC BY-NC 4.0 model: **internal testing only** |
| Sinhala | No offline Sinhala TTS that the speech models understand was found (MMS has no `sin`; two community VITS fine-tunes produced audio the ASR returns empty for). The gate reuses `infra/whisper/testdata/si-slr30-sin_6897_3117231257.ogg`: OpenSLR SLR30 (Google, https://www.openslr.org/30/), a TTS-corpus recording of a native speaker. | CC BY-SA 4.0 (kept for internal testing, Harsha 2026-10-12) |

The MMS-TTS models (https://huggingface.co/facebook/mms-tts-eng, `…/mms-tts-tam`) are licensed
CC BY-NC 4.0 (model card `license: cc-by-nc-4.0`). Generated 2026-10-12 in a throwaway container
from the speech image (transformers `VitsModel`).

**Next round:** real Sinhala, Tamil and English clips will be recorded in-house with a colleague
(consented, internal testing only) and replace the synthetic ones.
