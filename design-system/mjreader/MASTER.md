# MJReader — native reading design

Selected with ui-ux-pro-max from the verified Dark Mode (OLED) and E-Ink / Paper style entries. The generic landing-page recommendation was unsuitable for this native reader.

Use Android Views with branded control drawing and dialogs, preserving native accessibility and native file selection. The library carries the visual identity. Reading prioritizes text and hides controls on demand.

- Launcher icons follow the shared [MJ family rules](../mj-family/ICONS.md): graphite background, a gradient open book and a small MJ mark; adaptive masks and monochrome theming are supplied by Android.
- Semantic colors live in ReaderOptions. Dark, Dusk, paper and sepia have independent contrast-safe text and accent pairs.
- Menu actions, settings sections and playback controls share 24-unit outline icons from ReaderIcon. Icons inherit semantic foreground colors; adjacent text supplies the accessible name.
- Android controls have a minimum 48 dp target. Adjacent buttons have 8 dp spacing. Icon-only controls use native vector drawing and accessible descriptions.
- Body and UI text use sp. Large text reduces the number of library columns according to available width. Small-height reading layouts place mode switching first in the reading menu.
- Library and catalog mastheads stay fixed. The catalog search field also stays fixed as results scroll. The bottom navigation connects Library, Search, Downloads and Settings, with outline icons, a tonal active indicator and 48 dp minimum targets. From 0.9.1 the labels are accessible names, and the dock uses the larger of the system bottom inset and 8 dp from 0.9.3. Hide the dock while the IME is open.
- Catalog book cards use a typographic book tile, genre, title/author, description excerpt and supplied download metadata. Details show the full available description and publication metadata; never invent missing fields. Download ordering applies to loaded book entries with supplied counters, preserves navigation feeds and retains the visible book during paging.
- Library rows are virtualized through ListView. Covers use bounded decoding and a 24-entry bitmap cache.
- Reading offers explicit Scroll (default) and Pages modes. Pages uses one page or an adaptive two-page spread with a quiet central rule. Scroll hides footer arrows. Pages turn through horizontal swipes, arrows, keyboard actions or optional taps in the outer quarter of each edge. Arrows reflect the book boundaries. Center taps toggle panels. Turns animate with a short 180 ms horizontal slide of existing layouts. System reduced motion disables the turn animation. Page turns use complete line boundaries and the full-text anchor stays fixed during resize. Scrolling keeps a bounded reading width. Controls respect system bars and screen insets.
- Settings uses an overview with rows for Appearance, RSVP, library layout, theme and updates. Preference rows show the current value and open a choice dialog. Reading mode, typeface and size share the first Appearance group. Groups use 16 dp padding and an outline icon alongside the heading. Back returns to the overview or the same book position, depending on where settings opened. Each book can override shared settings.
- Dialog headers keep a 48 dp close icon fixed at the top, outside the scrolling content and actions. Dismiss-only Cancel/Close/Done actions move to that header. Save, download, delete and Later remain explicit actions. Headers cap long titles at two lines. From 400 dp at normal font sizes two actions share a row; narrower windows or font scale 1.6+ stack them. Confirmation comes last with 8 dp between actions. Destructive actions use the active theme's error foreground.
- Library cards open on tap, and long press opens book actions, with no duplicate Read action. Use an adaptive grid: normally two columns on phones, four at an 800 dp window, up to six on tablets. Cover ratio is 2:3, FIT_CENTER preserves the entire image. Missing covers use a bounded typographic cover with the full title (up to three lines), four muted palettes and simple geometric artwork. Keep a 48 dp book menu separate from the main card action. Large system fonts use fewer, wider columns, falling back to list layout on narrow windows.
- Reader controls overlay a fixed reading viewport and fade over 200 ms without sliding. Center taps never resize, repaginate or scroll the book. Reversals continue from current opacity; stable system-bar insets prevent jumps when immersive mode changes. Hidden controls are inaccessible; reduced motion switches immediately.
- Below 400 dp window height, text-reader header and footer each fit one 48 dp row, without vertical panel padding or header gaps. The header shows the chapter, with the book title retained in its accessible contents label. Progress uses a short percentage/time label and keeps its full chapter-time accessibility description. Controls retain 48 dp targets and the fixed reading viewport.
- Press feedback uses immediate tonal states with a visible focus outline. Do not fade entire screens. Loading uses a lightweight animated ring, static when system animations are disabled.
- Reading text supports 10–36 sp; controls retain their 48 dp targets. Local footnote links use accent color and underline, open a themed dialog, and take precedence over page-edge taps. Center and edge navigation do not fire when a footnote is opened.
- Native file selection supports multiple files, visible import feedback and per-file results.
- Deletion is reversible through Deleted. Invalid form fields retain input and show an inline error.
- Verify light and dark independently, portrait and landscape, large font scale, reduced motion, book position preservation, and live Android behavior before delivery.

## Research for 0.4.1

[Android grids and units](https://developer.android.com/design/ui/mobile/guides/layout-and-content/grids-and-units) supports modular galleries, adaptive columns, consistent image ratios and 4/8 dp spacing. [Android theme guidance](https://developer.android.com/design/ui/mobile/guides/styles/themes) and [dark theme guidance](https://developer.android.com/develop/ui/views/theming/darktheme) inform paired semantic colors. The skill's grid searches returned web-only matches, so native grid decisions follow the Android guidance instead.

## Reading screen, 0.5.0

Compact chapter header; quick appearance behind the type icon. Footer has contextual progress and four evenly spaced, centered icon actions on a separate rounded surface. Press, hover and focus stay inside each button; accessible labels and tooltips name all actions. The overflow menu is limited to five destinations. Native keyboard focus is set for editable dialogs. Dusk replaces the visually redundant AMOLED palette with plum backgrounds. Typography spans preserve every source offset while adding heading hierarchy and deliberate paragraph gaps.

[Moon+ Reader](https://www.moondownload.com/) informed hyphenation, paragraph spacing and optional volume-key paging. [ReadEra](https://readera.org/index.html) informed quick bookmarks and saved quotes. MJReader's quote action currently targets a paragraph through its own dialog. Remaining-time estimates use an explicit 250 wpm assumption.

- App updates use the shared colors, outline icons, tonal feedback and accessible switch drawing. A scrolling update screen keeps notes/actions reachable on short windows; install and unknown-source permission remain explicit native Android steps.

- Catalog cards limit previews to two lines, combine supplied file facts, and put Details / Download in one row; windows narrower than 400 dp or font scale 1.6 and above stack those actions. Completed downloads hide transfer meters. Empty catalog/download screens provide a direct browse action. The reader chapter header opens contents; header actions have explicit 8 dp gaps.

- Masthead settings icons have no contrasting resting frame: use the screen background, retaining tonal press feedback, keyboard focus and a 48 dp target.

- Text size has a bounded live excerpt next to its slider. Preview updates during dragging; book pagination reflows on release. Quick typography uses a bottom-aligned undimmed sheet; short wide windows put preview and slider side by side. Other appearance controls remain in full settings.
- Library uses one fixed title without a repeated heading or slogan. Continue-reading cards use a 40×60 dp cover, title and combined action/progress caption; at 740 dp and wider with normal font scale they share a row with search. Large fonts keep the vertical layout.
- RSVP prioritizes the word, Start/Pause and speed. Seek and Settings share one compact row with quiet resting backgrounds, start-aligned labels and 48 dp targets; narrow windows with large fonts stack them. Position slider, ±10-word steps and sentence navigation sit in an explicitly expandable Seek group, with a chevron that reflects its state. The mode explains the RSVP acronym through “one word at a time”.
- Quick type preview uses one line in 64 dp at normal phone sizes, 96 dp for large fonts/short windows; full settings use a 96 dp excerpt. Preview uses one line at large text sizes to avoid a clipped second line.
- Matching book/chapter titles appear once in the reader; distinct chapters retain book context. Footnote spotlight uses a light 4 dp blur and 22% tint while keeping the source sentence sharp. Reading panel/page animations retain their existing timing and reduced-motion behavior.

- Below 400 dp, library shelf filters get a full row, with filter and sort beside the shelf heading. Preserve 48 dp targets and 8 dp gaps.

## MJWatch alignment, 0.8.20

- The startup banner uses the MJ monogram and open book, the dark family canvas and paired orbit lines.
- Library filters and selected settings/catalog tabs use a softly tinted surface, with a contrast-safe foreground for each theme. Filled accents identify primary actions. Search uses the higher surface role.
- The continue-reading card shows the saved percentage and a narrow progress line. Settings sections use small accent headings above their rounded groups. The catalog title stays in its fixed masthead on short windows.

## MJWatch alignment, 0.8.22

- Dark and Light filters use the MJWatch lime selection colors. Shared UI actions use 24 dp outline icons with accessible names. The launcher uses the shared MJ monogram, with an open book for Reader and a filmstrip for Watch.
- Settings cards use a title and icon inside the surface, concise guidance and inline selectors. The update screen groups installed version, transfer status and update checks. Continue reading and the installed version card use the family surface gradient.
- Downloads open from the library masthead and the catalog. Cards show the title, format, confirmed bytes and percentage when the response supplies a length. A completed download offers Read. Page-loading errors keep existing results and offer Retry.
- The Android 16 download notification uses the native progress style with the book marker, measured speed and estimated remaining time when available. Tap it to open Downloads.
- The fresh-launch wordmark and loading label enter in stages. A 420 ms dissolve reveals the stationary library when rendering and the orbit entrance are ready. Reduced motion exposes the library immediately on readiness. Restoration and direct file opens skip the banner.

## Navigation and reading, 0.9.0

- The OPDS search field uses a rounded outline, a leading search icon, clear and submit actions. Title and author are explicit choices. Preserve the draft and result position when switching destinations. Show Retry with any existing results when a request fails.
- At regular heights the reader's footer labels Chapters, Bookmark, Theme and RSVP using the same icon-over-label arrangement as the bottom navigation. The short-window footer keeps its compact arrows and progress.
- The bundled Roboto face is shared by scroll layouts, page/spread layouts, quick previews and RSVP when selected. Its OFL notice is included with the app.

## Graphite family, 0.9.1

- The library masthead uses **MJReader** with a lavender MJ and keeps the add action at the trailing edge.
- The default dark theme uses graphite with violet surfaces. Lavender is the initial accent when the user has no saved accent. Preserve the four explicit accents and all reading theme and layout settings. Paper, Dusk and Sepia retain their own reading palettes.
- Lavender-to-pink gradients mark the add button, selected library chip, selected destination and library progress when the graphite lavender palette is active. The reading canvas uses a solid background. Reading and navigation toolbars use centered outline icons, accessible names, tooltips and 48 dp targets.
- Launch starts with graphite, gradient corner shapes, a small MJ mark and the **MJ Reader** wordmark. The gradient book assembles above the static mark. Readiness interrupts the entrance and starts the 160 ms reveal. Reduced motion skips both animations.

## Accent and launch corrections, 0.9.2

- Accent index zero now renders Pink across all themes. The saved index stays in place. Ice, Lavender
  and Amber keep their indices. Lavender remains the default when no option was saved.
- Android download notification progress uses the family lavender color for the system's light or dark appearance.
- On a cold launcher start, assemble the book for 280 ms after the system splash and reveal the ready library
  over 100 ms. Warm launches, Android reduced motion and external opens skip the custom banner.
- The two colored pages slide and turn inward from opposite sides to meet at the spine. The small MJ mark
  and wordmark stay fixed while the gradient corner shapes shift. The startup identity fades out before the
  stationary library and its masthead become visible.

## Light-orbit launch and bottom panels, 0.9.3

- The system splash fades into two thin lavender and pink arcs on matching graphite. The arcs and small
  particles converge once as the two pages meet at the spine. The assembled mark settles by 700 ms.
- A 400 ms diagonal light pass and 8% emblem approach reveal the ready library. Its masthead appears
  after the centered identity fades. Use the existing loading indicator if the library is still busy.
- Authoring comparisons use continuous Android recordings of signed 0.9.0 and the light-orbit build.
- The navigation dock and text-reader footer use the larger of the system bottom inset and 8 dp.
  This leaves space when the gesture indicator is hidden and uses the system spacing when it is shown.
- The navigation dock uses the shared rounded panel surface with a 1 dp tonal outline. Scrollable content extends behind the dock, with bottom padding so the last item can scroll clear of it.
- Reading actions use local tonal hover and press fills and a distinct keyboard focus outline.

## Navigation and page motion, 0.9.6

- Follow MJWatch's short content entrance on explicit tab and settings-section changes, with an 8 dp offset and 240 ms settle. Keep the masthead, bottom dock and window stationary. Data refresh, resize and reduced motion skip the entrance.
- Top-level destinations share the same masthead minimum height and dock width. The dock hides while the keyboard is visible, and the scrolling viewport ends at the keyboard.
- On two-page spreads, the central rule stays fixed as the text slides. When a slide is in progress, retain the latest page-turn request and apply it at the next page boundary. Reserve room for both reader panels outside the fixed text viewport, including while they are hidden. Keep header and progress height stable across page turns.
