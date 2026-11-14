# MJ app family — instructions for agents

Read this guide before creating or changing an MJ launcher icon. The reader is
our first app; future apps reuse its visual foundation. This guide and
[reader-icon.svg](reader-icon.svg) are the portable handoff to another MJ repo.

## Shared identity

- Keep the **MJ monogram**, its path, position, stroke and proportions unchanged.
  Copy `mj-monogram` from the SVG; do not substitute font glyphs.
- Use a flat full-bleed background **#141925** and foreground **#C7FF68**.
  These are family brand colors, independent of an app's selected reading theme.
- Change only `app-symbol` to communicate the app's purpose: the reader uses an
  open book. A future anime player can use a simple play symbol in the same slot.
  This is guidance, not a request to implement or name another app.
- No gradients, shadows, outer borders, badges, extra lettering or detailed art.
  Do not bake a circle or rounded-square mask into adaptive layers.

## Geometry and template

The SVG is a vector master, not a screenshot. Android vectors must match it.

| Element | Specification on the 108 × 108 canvas |
| --- | --- |
| Background | Fills the entire canvas, including adaptive overscan |
| MJ | Exact `mj-monogram` path; 4.5-unit stroke |
| App symbol | Slot x=42–66, y=66–80; optically centered at x=54 |
| Symbol stroke | 2.5 units; rounded caps and joins; no fill |
| Monogram stroke | Rounded caps and joins; no fill |
| Safe area | All meaningful strokes inside a circle centered at (54,54), radius 33 |

Keep a visible gap between MJ and the symbol. A replacement symbol should have
similar visual weight to the book; a play triangle may be narrower than the slot.
Do not stretch a symbol to fill the slot or enlarge the monogram for one app.
At a 48 px launcher size, MJ and the app symbol must both remain recognizable.

## Android resources

MJReader supports API 26+. Follow the existing native resource arrangement:

- `app/src/main/res/drawable/ic_launcher_foreground.xml`: transparent vector with
  MJ and the book, using the paths and strokes in the SVG master.
- `app/src/main/res/values/colors.xml`: `mj_icon_background`.
- `app/src/main/res/drawable/ic_launcher.xml`: adaptive background + foreground.
- `app/src/main/res/drawable-v33/ic_launcher.xml`: same layers plus `monochrome`.
  Reuse the foreground vector: Android uses its alpha silhouette and supplies
  the user's themed colors. Never include an opaque background in this layer.
- Both manifest `icon` and `roundIcon` reference the adaptive icon. The launcher
  chooses the mask. Preserve application ID and signing identity.
- Notification small icons are separate white silhouettes on transparency.
  Never pass an adaptive icon to `Notification.Builder.setSmallIcon`.
  MJReader uses `drawable/ic_notification.xml`, an open-book silhouette.

When copying this design to another app, replace the app symbol in the SVG and
foreground vector, then provide that app's separate notification silhouette.
Do not introduce an image library, font dependency, runtime renderer or bitmap
asset pipeline for these few paths.

## Review and delivery

1. Compare the SVG master and Android vector paths, strokes and colors.
2. Render previews from those exact paths: circle and rounded square, plus
   monochrome on light and dark themed backgrounds. Android's normal visible
   mask occupies roughly the central 72 × 72 area (viewBox `18 18 72 72`);
   previewing the entire 108-unit layer would make the art look too small.
3. Inspect at 32, 48 and 64 px as well as enlarged. Verify no clipping or loss of
   the app symbol. Keep significant artwork within the 66-unit safe circle.
4. Run `./gradlew assembleDebug lintDebug`. If an emulator is available, also
   check the installed launcher icon, themed icons and a download notification;
   state clearly which checks actually ran.
5. Update the SVG, this guide if the family rules changed, and the changelog.
   Put generated review images under ignored `dist/`; do not commit APKs.
   Publishing or bumping the version requires the user's release scope.

Reference: [Android adaptive icons](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive).
