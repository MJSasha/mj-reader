# MJ app family launcher icons

The graphite family uses a dark, full-bleed canvas, one large app symbol in the app's
accent colors, and a small neutral MJ mark below it. The visual reference is variant 02
of [the family design](https://github.com/MJSasha/mj-watch/tree/e462a61/design/mj-family/redesign-concept).

MJReader uses a lavender-to-pink open book. [reader-icon.svg](reader-icon.svg) is the
vector master. Keep strokes and silhouettes recognizable at launcher sizes, stay inside
the centered 66-unit safe circle on the 108 × 108 canvas, and let Android supply the mask.

## Android resources

- `drawable/ic_launcher_foreground.xml` follows the SVG book layers and MJ path.
- `values/colors.xml` defines the graphite adaptive background and matching startup background.
- `drawable/ic_launcher.xml` supplies the adaptive background and foreground.
- `drawable-v33/ic_launcher.xml` also supplies `drawable/ic_launcher_monochrome.xml`.
  This layer uses a transparent background and a narrow gutter between the book's pages,
  so Android's single themed color preserves the silhouette.
- The launcher and round launcher references in the manifest use the same adaptive icon.
  Preserve application ID and signing identity.
- Android 12 and above use the foreground mark in their system splash. The custom launch
  artwork reuses it and adds lavender and pink corner shapes.
- The download notification uses Android's download arrow and the separate
  `drawable/ic_notification.xml` book marker. Notification icons use white on transparency.

## Review

Compare the master and Android vector paths. Check circle and rounded-square masks and
light and dark themed icons at 32, 48 and 64 px. Android's visible mask typically occupies
the central 72 × 72 area (viewBox `18 18 72 72`). Run the Android build and lint, inspect the
installed icon and launch when an emulator is available, and keep generated review images
under ignored `dist/`.

[Android adaptive icons](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive)
