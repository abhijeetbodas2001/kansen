# Instrument models

`analyze_instruments.py` measures reference recordings into harmonic amplitudes,
relative phases and residual noise envelopes. The app renders oscillators and
filtered random noise from these parameters. Reference waveforms are not bundled.

Use `uv` for Python. Analysis dependencies can live outside the repository and
system Python installation:

```sh
uv pip install --target /tmp/kansen-analysis-libs numpy scipy
PYTHONPATH=/tmp/kansen-analysis-libs uv run --no-project --no-config python \
  tools/analyze_instruments.py /path/to/references \
  app/src/main/assets/synthesis/instruments.bin
```

Reference filenames are listed in the script. Sources and licenses are in
`app/src/main/assets/synthesis/ATTRIBUTION.txt`. Convert downloads to mono
44.1 kHz WAV with ffmpeg before analysis. The generated `sources.json` records the input hash, source region and measured
ringing frequency for each profile. `model_bank.py` writes compact float envelopes
into KSN4; the app streams these directly into arrays instead of constructing a
large JSON object tree. JSON output is still supported for offline inspection.

Harmonium uses 26 measured keys between C3 and D5 (F♯4 is interpolated).
Above D5, generated profiles preserve the measured body spectral envelope rather
than shifting all brightness upwards with pitch. The bounded spectral warp is
selected by predicting the measured upper-register profiles from lower ones;
this fitting score is not independent evidence of perceptual realism. These high
notes remain synthetic extensions, identified in `sources.json`.

Common pitch drift is removed while preserving relative harmonic phase and
amplitude changes. A phase-aligned overlap joins the sustain loop. Its overall
envelope is levelled after the attack to avoid bellows-pressure dips. Sustained
RMS is capped at 0.24, with 0.96 peak headroom, to avoid louder upper-register reeds.

Pure tone uses a sine oscillator without an instrument model. Both voices fade
in over one second and sustain until playback stops. The binary bank contains
only harmonium profiles; their reference waveforms are not included.

To evaluate a change, compare loudness-matched reference and rendered excerpts,
including attacks and held loops. Spectral similarity, pitch and clipping checks
can catch errors but do not establish perceptual realism.
