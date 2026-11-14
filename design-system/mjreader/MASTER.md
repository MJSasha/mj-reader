# MJReader — native reading design

Selected with ui-ux-pro-max from the verified Dark Mode (OLED) and E-Ink / Paper style entries. The generic landing-page recommendation was unsuitable for this native reader.

Use Android Views with branded control drawing and dialogs, preserving native accessibility and native file selection. The library carries the visual identity. Reading prioritizes text and hides controls on demand.

- Launcher icons follow the shared [MJ family rules](../mj-family/ICONS.md): fixed MJ monogram, dark background, lime foreground and a small app-specific symbol. The reader uses an open book; adaptive masks and monochrome theming are supplied by Android.
- Semantic colors live in ReaderOptions. Dark, Dusk, paper and sepia have independent contrast-safe text and accent pairs.
- Menu actions, settings sections and playback controls share 24-unit outline icons from ReaderIcon. Icons inherit semantic foreground colors; adjacent text supplies the accessible name.
- Android controls have a minimum 48 dp target. Adjacent buttons have 8 dp spacing. Icon-only controls use native vector drawing and accessible descriptions.
- Body and UI text use sp. Large text reduces the number of library columns according to available width. Small-height reading layouts place mode switching first in the reading menu.
- Library and catalog mastheads stay fixed while search and content scroll. Bottom actions sit on a rounded surface with a 12 dp content gap; catalog destinations use inline icons and labels, 48 dp minimum height and a filled accent selection.
- Catalog book cards use a typographic book tile, genre, title/author, description excerpt and supplied download metadata. Details show the full available description and publication metadata; never invent missing fields. Download ordering applies to loaded book entries with supplied counters, preserves navigation feeds and retains the visible book during paging.
- Library rows are virtualized through ListView. Covers use bounded decoding and a 24-entry bitmap cache.
- Reading offers explicit Scroll (default) and Pages modes. Pages uses one page or an adaptive two-page spread with a quiet central rule. Scroll hides footer arrows. Pages turn through arrows or taps in the outer quarter of each edge (plus keyboard/accessibility); center taps toggle panels. Turns animate with a short 180 ms horizontal slide of existing layouts. No paper curl, page textures or swipe gestures. System reduced motion disables the turn animation. Page turns use complete line boundaries; the full-text anchor stays fixed during resize. Scrolling keeps a bounded reading width. Controls respect system bars and screen insets.
- Settings occupy a separate route with a segmented tab surface, preview, grouped cards, inline expanding selectors, theme swatches and custom switches. Reading mode, typeface and size share the first appearance group. Groups have 16 dp internal padding on all sides. Back returns to the same book position. Each book can override shared settings; the scope hint explains that turning overrides off restores shared settings.
- Dialog headers keep a 48 dp close icon fixed at the top, outside the scrolling content and actions. Dismiss-only Cancel/Close/Done actions move to that header; save, download and delete remain explicit actions. Headers cap long titles at two lines. Two actions share a row at normal font sizes (confirmation on the right); large fonts stack them. Keep 8 dp between actions. Colors come from the active reader theme.
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

- Below 400 dp, library shelf filters get a full row; filter/sort actions sit beside the shelf heading. Bottom library actions use text without redundant icons, and stack at font scale 1.6+. Narrow settings tabs omit icons and give Appearance more width; large fonts stack them. Catalog search controls stack with large fonts, while its bottom destinations keep labels and omit icons. Preserve 48 dp targets and 8 dp gaps.
