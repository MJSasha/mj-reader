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
- Settings occupy a separate route with a segmented tab surface, preview, grouped cards, inline expanding selectors, theme swatches and custom switches. Reading mode and typography are the first appearance groups. Groups have 16 dp internal padding on all sides. Back returns to the same book position. Each book can override shared settings.
- Dialog headers keep a 48 dp close icon fixed at the top, outside the scrolling content and actions. Dismiss-only Cancel/Close/Done actions move to that header; save, download and delete remain explicit actions. Headers cap long titles at two lines. Two actions share a row at normal font sizes (confirmation on the right); large fonts stack them. Keep 8 dp between actions. Colors come from the active reader theme.
- Library cards open on tap, and long press opens book actions, with no duplicate Read action. Use an adaptive grid: normally two columns on phones, four at an 800 dp window, up to six on tablets. Cover ratio is 2:3, FIT_CENTER preserves the entire image. Missing covers use a bounded typographic cover with the full title (up to three lines), four muted palettes and simple geometric artwork. Keep a 48 dp book menu separate from the main card action. Large system fonts use fewer, wider columns, falling back to list layout on narrow windows.
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

- Below 400 dp, library shelf filters get a full row; filter/sort actions sit beside the shelf heading. Bottom library actions use text without redundant icons, and stack at font scale 1.6+. Narrow settings tabs omit icons and give Appearance more width; large fonts stack them. Catalog search controls stack with large fonts, while its bottom destinations keep labels and omit icons. Preserve 48 dp targets and 8 dp gaps.
