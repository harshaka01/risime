# Call-transcription gate clips (scripts/call-transcribe-test)

Mono 16 kHz Ogg/Opus, 24 kbit/s. Injected in place of the microphone by the debug-only
`MIC_INJECT` seam (debug builds only), never played by a person.

| File | Source |
|---|---|
| `en-tts.ogg` | Synthetic: MMS-TTS `facebook/mms-tts-eng` (VITS), text "Let's meet at the office tomorrow at ten. We will send the revised quote on Friday." |
| `ta-tts.ogg` | Synthetic: MMS-TTS `facebook/mms-tts-tam`, text "நாளை காலை பத்து மணிக்கு அலுவலகத்தில் சந்திப்போம். திருத்திய விலைப்பட்டியலை வெள்ளிக்கிழமை அனுப்புவோம்." |
| Sinhala | No offline Sinhala TTS that the speech models understand was found (MMS has no `sin`; two community VITS fine-tunes produced audio the ASR returns empty for). The gate reuses `infra/whisper/testdata/si-slr30-*.ogg` (OpenSLR SLR30, a TTS-corpus recording, CC BY-SA 4.0), the speech service's own test clip. |

The MMS-TTS models are CC BY-NC 4.0; the clips are used only for internal testing.
Generated 2026-10-12 in a throwaway container from the speech image (transformers `VitsModel`).
