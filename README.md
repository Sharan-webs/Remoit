# remoit  (project: Watermarko)

Removes text, logos, dates and captions from **videos (up to 2 h 30 min)** and **images**, on the phone.
Same look as PIXEL•X. No server, nothing is uploaded.

## How it works

1. **Upload** an image or a video (Home screen, scroll down).
2. **Mark page**
   * Video player with **10 s back / play-pause / 10 s forward** and a seek bar.
   * **Create box**: tap on the text to create a box. Drag the `><` side buttons (or corners) to resize. Long-press the middle and drag to move.
   * **Pen**: doodle over the text (red, 50 % opacity).
   * **Options**: pen size, undo, auto-find text (on-device ML Kit), delete box, remove only between two times, Smart / Whole box mode.
3. **Process**: runs in a foreground service (keeps going when the app is closed).

## The remover (Inpaint.kt)

* Smart box: the clean background under the box is estimated from its surroundings, only pixels that really differ (the text) are replaced. The rest of the box is untouched.
* Fill: multi-scale push-pull + relaxation continues colours and gradients from the surrounding pixels.
* Texture: fine structure is mirrored from the nearest clean pixels and grain matching the surrounding noise is added, so the patch does not look smooth or "erased".
* Soft feathered border, no visible rectangle.
* Static scenes reuse the previous result (stable, fast). Moving scenes are recomputed every frame.
* Only the marked areas are touched; the rest of every frame is copied as-is.

## Quality (VideoPipeline.kt)

* Same resolution, frame rate, rotation, colour range/standard as the source.
* Re-encoded with MediaCodec at about 1.35x the source bitrate (same codec, H.264 or HEVC, when the phone has an encoder).
* Audio is copied without re-encoding.
* Not supported yet: HDR / 10-bit videos (the app tells you instead of losing quality).

## Build on GitHub (no Android Studio)

1. Upload everything in this folder to a GitHub repo (keep `.github`).
2. Actions > **Build remoit APK** > Run workflow. Download the APK from Artifacts.
3. Optional secrets for a release signature: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.

## Where to change things

* Add-on code: hash in `Store.kt` (`CODE_HASH`, SHA-256 of the upper-cased code).
* Max video length: `VideoPipeline.MAX_MS`.
* Output bitrate factor: `createEncoder` in `VideoPipeline.kt` (`mult`).
* Removal strength / sensitivity: `ItemProcessor.recompute` in `Inpaint.kt` (`thr`).
* Credits per day: `Credits.MAX`.
* Owner link: `Texts.OWNER_IG` / `Texts.IG_URL`.
