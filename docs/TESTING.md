# Testing

## Prerequisites

JDK 17, Python 3.10+, Android SDK Platform 36, Build Tools 35.0.0, platform-tools.
Use the committed Gradle Wrapper. Set `ANDROID_HOME` (or an untracked `local.properties`).

```sh
bash scripts/checks.sh
```

This checks the shared version, the CI result parser, pure-Java RU/EN reading logic,
compiles app/test APKs and runs Android lint. Lint errors fail the build. The current
baseline has warnings (localization formatting, tool versions and storage APIs);
they are not represented as a clean zero-warning result.

CI splits the same five suites across two independent hosted emulators:
`smoke,catalog` and `adaptive,visual,updates`. Neither group is optional.
Artifacts are `android-reports-reading-catalog` and
`android-reports-interface-fold-updates`. Release reuses successful main CI for
its exact commit instead of running those suites again.

## Android suites

The project uses a custom `Instrumentation`, not JUnit's Android runner.
Install both APKs on a **dedicated Android 16 emulator**:

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
python3 scripts/android-checks.py --device emulator-5554
```

The script rejects physical devices, selects English for label-based checks and sets
430×932 dp / density 160 / font scale 1, with the software keyboard enabled even when
the emulator exposes a hardware keyboard. Tests modify demo-book settings and create
fixtures. `--reset-app` additionally erases MJReader app data before each suite; CI
uses it only on the new hosted emulator. Do not use it on an emulator containing books
you want to keep. There is no implicit device selection.

| Suite | Coverage | External requirements |
| --- | --- | --- |
| `updates` | Release/prerelease ordering, invalid URL/hash/size/draft rejection, APK identity/signature/version guards, daily checks, light/dark update UI, persisted DownloadManager completion/cancellation | Local fixtures only |
| `smoke` | External VIEW/SEND open/duplicate handling, 10 sp pages and off-marker note taps, nearest-note hit targets, EPUB/FB2 footnotes, standalone/nested/named/XML anchors, adjacent-note boundaries, paragraph preservation and repair, Batch/invalid imports, targeted reimport menu/cancel/wrong-file rejection, TXT/FB2 encodings, FB2.ZIP/embedded assets/chapter offsets/repair/invalid XML, EPUB cover/navigation, duplicates/repair, metadata, collections, reversible deletion, search, bookmarks, RU/EN RSVP, persistence | None |
| `catalog` | Local HTTPS OPDS, slow-mirror hedge/cancellation, truncated download retry, retries/failover, next pages, cover/chapter import, cancellation/retry, background download, zipped FB2 download/import, keyboard, themes, retained pages | Local test server only |
| `visual` | Footnote spotlight on narrow/spread/short windows and font scale 2, long-note scrolling, dismissal and anchor preservation; pinned dialog close at both scroll extremes, dismiss callbacks without save/delete, keyboard, narrow/short windows and large fonts; live text-size preview during dragging, reader anchor preservation, simultaneous preview/slider visibility in short windows, resume/search/quick-filter/deleted-book behavior, library/settings/dialogs, custom selectors, reachable actions, light/dark, font scale 2, short landscape, settings restoration | None |
| `adaptive` | 320/360 dp narrow windows, 1080×2520 px at 420 dpi (Flip 7 inner-display proportions), RU/EN button labels, font scale 2, Browse surface, settings tabs, 360/540/740/800/900/1040 dp windows, minor and major resizes, reader position/fullscreen, exact page seams, arrow-driven forward/back slides in both themes, left/right edge taps and animated center controls on phones/spreads including fullscreen, rapid control reversals and scroll anchors, no scroll arrows, interrupted turns/reduced motion, one/two-page reflow, illustrated EPUB repair, large-text pages, catalog draft/pages/scroll, control states/targets | Emulator resize permission |

Run a targeted subset with `--suites catalog,adaptive`. Reports are written under
`dist/reports/android`: plain logs, JUnit XML and screenshots by suite. A missing `PASS:`
marker is a failure even if `adb` exits zero. A failed suite also saves the last 4000
Android log lines as `<suite>-logcat.txt` for assertion/crash diagnostics on the dedicated emulator. CI uploads those reports on failures too.

## Manual and optional checks

- Light and dark independently; also Dusk and Sepia when changing color tokens.
- Narrow phone portrait (320×747 and 360×840 dp), Flip 7 inner-display proportions (1080×2520 px at a test density of 420 dpi, about 411 dp wide), wide Fold/tablet, short landscape, font scale 2 and animations disabled. The test density is not a claim about Samsung’s default display scaling.
- The `adaptive` suite captures library, catalog, downloads, settings, scrolling and paged reading at narrow widths; checks prevent split button words, wrapped settings tabs, missing button surfaces and targets smaller than 48 dp.
- Actual tap/long press, menu actions, hover/focus, keyboard opening and dismissal.
- Resize while reading, searching and downloading; preserve user context.
- A physical Fold is still required to assess the actual hinge/Flex posture and device-specific behavior.

Raw runner examples (for diagnostics; inspect the printed result):

```sh
adb -s emulator-5554 shell am instrument -w -e catalog true -e live true com.mjreader.test/com.mjreader.ReaderSmokeTest
adb -s emulator-5554 shell am instrument -w -e visual true com.mjreader.test/com.mjreader.ReaderSmokeTest
```

`live` checks catalog/navigation availability without downloading an external book.
It is deliberately not a CI gate. `sample`, `reader` and `benchmark` are local regression
modes tied to the maintainer's private sample EPUB; that file is not included in this
repository and those modes are not portable CI suites.

## Documentation screenshots

`-e showcase true` renders copies of the two original stories included in the app,
with synthetic book metadata. It does not use the maintainer's EPUB. Use a dedicated
emulator at 430×932 / 160 dpi / font scale 1, with app locale `ru`.

```sh
adb -s emulator-5554 shell am instrument -w -e showcase true com.mjreader.test/com.mjreader.ReaderSmokeTest
adb -s emulator-5554 exec-out run-as com.mjreader cat files/qa-showcase-library-dark.png > docs/images/library-dark.png
```

The other generated names are `library-light`, `reading`, `rsvp`, `settings` and `fold`.
Review images before committing. The full `dist/` history and personal-book captures
are intentionally excluded from Git.

## Test certificate

`app/src/androidTest/assets/catalog-test.p12` is a public, localhost-only fixture with
the test password `test-only`. It is not a signing credential. The test temporarily
trusts that server in its own process, restores the previous factory and cleans up.
Release packaging explicitly rejects `.p12`, signing files and EPUB files in the APK.

## Optional installer check

The `updates` instrumentation accepts `-e updateApk /data/local/tmp/mjreader-update-test.apk` for a locally generated APK with versionName `99.0.0`, a higher versionCode, package `com.mjreader`, `debuggable=false` and the same debug certificate as the installed debug app. This optional check validates an actual APK and opens the permission explanation (or Android installer when the dedicated emulator already permits it); it never confirms installation. Do not use a production library/device. Require the runner's `PASS:` marker. The portable `updates` suite does not need this file or GitHub access.
