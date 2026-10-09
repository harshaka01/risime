# whisper-smoke test clips

Mono 16 kHz Opus (24 kbit/s, `-application voip`, like call audio), re-encoded from the sources
below with ffmpeg. Used only by `scripts/whisper-smoke`.

| File | Source | Licence |
|---|---|---|
| `en-jfk.ogg` | J. F. Kennedy, inaugural address, 1961 ("And so, my fellow Americans, ask not what your country can do for you, ask what you can do for your country."), the `samples/jfk.wav` clip of whisper.cpp | Public domain (US federal government work) |
| `si-slr30-sin_6897_3117231257.ogg`, `si.txt` | OpenSLR SLR30 "High quality TTS data for Sinhala" (Google), utterance `sin_6897_3117231257`, https://www.openslr.org/30/ | CC BY-SA 4.0 |
| `ta-fleurs-10015420708072669120.ogg`, `ta.txt` | FLEURS (Google), `ta_in` test split, `10015420708072669120.wav`, https://huggingface.co/datasets/google/fleurs | CC BY 4.0 |

The si and ta clips are read speech by native speakers (not synthetic). The re-encoded clips and
reference texts are shared under the same licences as their sources.
