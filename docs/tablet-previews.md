# Tablet UI previews

Open `app/src/screenshotTest/kotlin/app/infinity/mpvz/ui/preview/LibraryTabletPreviews.kt`
in Android Studio's Design or Split view. Select the `standardDebug` variant.

The previews cover selected folders, the empty library, folder rows and saved
playlists in Japanese, at 800 × 1280 dp, 1280 × 800 dp and portrait dark mode.
Names and counts are sample data. The selected-folder screen uses the same
stateless content as the app. Library/playlist previews wrap the production
cards, toolbar and navigation bar in a preview harness; they do not run the
application's navigation, database, storage scanner or playback engine.

Render PNGs without an emulator or Android Studio (JDK 21 and the project's
Android SDK packages are required; the existing Docker build environment works):

```sh
./gradlew :app:updateStandardDebugScreenshotTest \
  -PtargetAbi=arm64-v8a -PenableX86=false
```

Images are generated under `app/src/screenshotTestStandardDebug/reference`.
The task name says “update”, but generating images does **not** mean the design
has been approved. Review the images before accepting references or releasing.
The preview source set is excluded from the shipped application.

The screenshot plugin is pinned to `0.0.1-alpha16`, using the standalone setup
supported by the project's AGP 9.3.2. It renders through Android layoutlib on the
host. This adds no emulator or device requirement.

Create a local gallery after rendering:

```sh
python3 scripts/tablet-preview-gallery.py
python3 -m http.server 8765 --bind 127.0.0.1 --directory app/build/tablet-previews
```

Open `http://127.0.0.1:8765`. The gallery switches between portrait, landscape
and dark mode, and each image opens at full size. No external assets are used.
