# FilmPilot

A personal Android film camera: a 24-exposure roll, a film look from a 3D LUT, grain,
vignette and exposure compensation, saved to `Pictures/FilmPilot`.

Kotlin + Jetpack Compose + CameraX, min SDK 26 (Android 8.0).

## Phase 1 (this build)

- CameraX viewfinder (back camera, 4:3) and capture.
- Each frame is "developed" in the background: exposure → film LUT → vignette → grain,
  then saved as a JPEG (quality 95, original EXIF date/exposure/lens/GPS kept) to the gallery.
- Film picker: every `.cube` file in `app/src/main/assets/luts/`, plus "Neutral".
- Adjust sheet: exposure −2…+2 EV (⅓ stops), grain strength, vignette strength.
- Frame counter, 24 shots per roll. Changing film mid-roll asks to rewind and load a new roll.
  The roll and settings survive app restarts.

The live preview is not processed yet; the look is applied to the saved photo. That's phase 2.

## Project layout

```
filmpilot/
├─ filmfx/                      Effects pipeline: pure Kotlin/JVM, no Android dependency
│  └─ src/main/kotlin/com/filmpilot/fx/
│     ├─ FilmPipeline.kt        per-pixel: exposure → LUT → vignette → grain (strip-able, thread-safe)
│     ├─ FilmParams.kt          all inputs for one frame (future shader uniforms)
│     ├─ Lut3D.kt, CubeParser.kt  .cube parsing + trilinear lookup
│     └─ Noise.kt, ColorMath.kt   integer-hash grain noise, sRGB ↔ linear
├─ app/                         Android app
│  ├─ src/main/assets/luts/     ← drop .cube files here
│  └─ src/main/java/com/filmpilot/app/
│     ├─ camera/                BitmapFilm (Bitmap ↔ pipeline), FilmDeveloper, GallerySaver
│     ├─ film/                  LutCatalog, roll + settings persistence
│     ├─ ui/                    CameraScreen, Viewfinder, film picker / adjust sheets
│     ├─ CameraViewModel.kt
│     └─ MainActivity.kt        permissions + theme
└─ tools/make_sample_lut.py     generates the sample LUT
```

Why `filmfx` is separate: every effect is a pure function of (pixel colour, normalized
position, `FilmParams`) and uses only shader-friendly maths (integer hash noise, smoothstep,
trilinear lookup in red-fastest order). Phase 2 can port `FilmPipeline.processRows` almost line
by line into a GLSL/AGSL fragment shader for a live preview, and keep the Kotlin version as the
reference implementation that the unit tests pin down.

## Adding films

1. Put a 3D `.cube` file in `app/src/main/assets/luts/` (e.g. `portra_ish.cube`).
2. Rebuild. It appears in the film picker under its `TITLE` line, or the prettified file name.

Most tools export `.cube`: Lightroom/Photoshop (Export Color Lookup), DaVinci Resolve, Affinity.
Sizes up to 65³ are fine; 33³ is a good balance. To make your own procedurally, edit the constants
in `tools/make_sample_lut.py` and run `python3 tools/make_sample_lut.py app/src/main/assets/luts/mine.cube`.

## Install on your phone over USB (adb)

**One-time phone setup**

1. *Settings → About phone*, tap **Build number** 7 times to enable Developer options.
2. *Settings → System → Developer options*, turn on **USB debugging**.
3. Plug the phone in, and on the phone accept **Allow USB debugging?** for this computer.

**One-time computer setup**: install the Android platform tools (`adb`):
- macOS: `brew install android-platform-tools`
- Windows: `winget install Google.PlatformTools`
- Linux: `sudo apt install adb`, or the zip from developer.android.com/tools/releases/platform-tools

Check the phone is visible: `adb devices` should list it as `device` (not `unauthorized`).

**Install**

Option A: use the APK CI builds on every push. Download `FilmPilot.apk` from this repo's
**Releases → filmpilot-latest** page, then:

```
adb install -r FilmPilot.apk
```

Option B: build it yourself (Android Studio, or JDK 17 + Android SDK):

```
cd filmpilot
./gradlew :app:installDebug          # builds and installs on the connected phone
# or
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

`-r` reinstalls over an existing copy and keeps its data. Every build is signed with the same
key (`app/filmpilot.keystore`), so updates always install over the previous version.

Launch it from the app drawer, or with `adb shell am start -n com.filmpilot.app/.MainActivity`.
Grant camera access on first launch. Photos appear in your gallery under **FilmPilot**.

Handy while testing: `adb logcat -s FilmPilot AndroidRuntime` shows the app's log and crashes.

## Development

```
# Effects module only: works without an Android SDK
./gradlew -PfxOnly :filmfx:test

# Render before/after PNGs of the look (optional: FX_PREVIEW_INPUT=photo.jpg)
FX_PREVIEW_DIR=/tmp/fx ./gradlew -PfxOnly :filmfx:test --tests '*PreviewRender*'
```

CI (`.github/workflows/filmpilot.yml`) runs the effects tests, builds the release APK, publishes
it as the `filmpilot-latest` release, then runs an emulator smoke test that shoots three frames
with the emulated camera and checks they reach `Pictures/FilmPilot`.

## Later phases (planned)

- Live film preview: port `FilmPipeline` to an OpenGL ES / AGSL shader on the camera stream.
- Lens presets: crop, barrel/pincushion distortion, lens-specific vignette.
- Camera presets: half-frame (two portrait frames per shot, 48-exposure roll), date stamp.
