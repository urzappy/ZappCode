package com.zapp.net.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// OpenCode shape, ZAPP skin - near-black canvas, violet accent kept on purpose.
//
// COLOUR PARITY, 2026-11-21. the owner: *"are you done in the collors all same as opencode pc?"*
//
// The answer was no, and these four values were why. Their dark block
// (`oc-2-COeEKe_J.js` @ 14 085 - the one whose `--v2-background-bg-base` is
// `--v2-grey-1100` = `#161616`) defines:
//
//     --v2-text-text-base   = --v2-grey-100 = #FAFAFA
//     --v2-text-text-muted  = --v2-grey-500 = #AEAEAE
//     --v2-text-text-faint  = --v2-grey-600 = #808080
//
// We were drawing body text at #EDEDED and labels at #A6A6A6. Neither is a token
// in their ramp at all - #A6A6A6 does not exist anywhere in it - so every label
// and every paragraph was a little darker than the PC. Measured on a real
// screenshot of this phone before the change: the reply body drew #EDEDED
// (16 661 px of it), where their `.error-card`/`text-part` rule resolves to
// `--v2-text-text-base` = #FAFAFA.
//
// The surfaces below are deliberately **not** theirs: `Bg #0A0A0A` against their
// `#161616` is the owner's own skin ("black and white"), and it is a design choice,
// not drift. Text and icon colours are theirs because that is what he compares.
//
// GREY REMOVAL, 2026-11-22. the owner: *"i dont like the gray color all gray i dont like remove it
// change darker or something their gray color i dont like all of them change it"*.
//
// He is right about what the screen was doing. Their ramp is a **neutral** grey (`#AEAEAE` is
// R=G=B) sitting on our much darker canvas, so every label, every note and every hairline was the
// same colour with no relation to the violet accent or to the black page. Neutral grey on near-black
// reads as "unfinished UI", not as hierarchy.
//
// The ramp below is **ours**, not theirs: each step keeps their *relative* order and roughly their
// contrast, but the hue is pulled to our accent's violet (262 degrees) at very low saturation and
// every step is darker. Nothing here is R=G=B any more.
//
//   token        was       now       contrast on Bg (was -> now)
//   TextMid      #AEAEAE   #9A99A6   8.92 -> 7.05
//   TextDim      #6E6E72   #6A6974   3.90 -> 3.66
//   TextFaint    #808080   #72717C   5.01 -> 4.12
//
// Their ordering is preserved (`faint` still brighter than `dim`), and every step stays above the
// 3:1 UI floor on `Surface2` as well (7.05/3.66/4.12 on `Bg`, 6.54/3.40/3.82 on `Surface2`).
//
// **Changed since Pass 31**: the diff palette below is ours now too. the owner, 2026-11-22: *"i hate the
// color red gren and gray to the files codes"* - so the PC's mint/red/grey `<diffs-container>` theme
// is replaced by the accent violet for additions and a warm sand for deletions. The old shipped
// values are still recorded in the block above the tokens.
//
// `Syntax.kt` was retinted in the same pass, for the same reason and by the same rule: the code
// inside a diff was still mint and grey.
val Bg = Color(0xFF0A0A0A)          // canvas (ours; theirs is #161616)
val Surface = Color(0xFF0C0C0F)     // chrome bars, composer (ours)
val Surface2 = Color(0xFF141419)    // user bubble, selected pill (ours)
val Line = Color(0xFF191920)        // hairlines / rails (ours)
val Border = Color(0xFF262630)      // input outlines (ours)
val TextPrimary = Color(0xFFFAFAFA) // --v2-text-text-base  = v2-grey-100 (dark), kept
val TextMid = Color(0xFF9A99A6)     // ours - their #AEAEAE is neutral grey, this is ink
val TextDim = Color(0xFF6A6974)     // ours - their dark ramp has no fourth level

// `--v2-text-text-faint` in the **dark** block is `v2-grey-600` = #808080. Their session-header
// usage pill is painted entirely in faint, not muted - it reads dimmer than the labels around it.
// Ours keeps that position but on the ink ramp: #72717C, 4.12:1 on `Bg`.
val TextFaint = Color(0xFF72717C)
// `progress-circle` tokens, dark block: track `--v2-background-bg-layer-04` = v2-grey-700
// #5C5C5C, arc `--v2-icon-icon-base` = v2-grey-400 #DBDBDB. Drawn with `Line` the track was
// #1E1E22 - a ring you cannot see. The track is the one place their neutral grey was most visible
// (a bright ring on a black card), so it moves onto the ink ramp too; the arc stays theirs.
val RingTrack = Color(0xFF4A4A55)
val RingArc = Color(0xFFDBDBDB)

// `--v2-icon-icon-base` in the **dark** block is `v2-grey-400` = #DBDBDB - the same step the ring
// arc uses. Every `ghost-muted` icon button switches to it when it is `pressed` or `expanded`, so a
// control that has opened reads brighter than its neighbours without going full white. Ours used
// `TextPrimary #FAFAFA`, which is why the review glyph's pane fill turned into a white slab.
val IconBase = Color(0xFFDBDBDB)
// `--v2-overlay-simple-overlay-pressed` in the dark block is `--v2-alpha-light-10` - white at 10%.
// Their `ghost-muted` open state paints this behind the 28px button as well as brightening the
// glyph, so the control has a body when it is on. The hover step is the same white at 6%.
val PressedOverlay = Color(0x1AFFFFFF)

// `--v2-background-bg-layer-01` and `-02` (dark block). These are the **elevated component**
// surfaces - every popover, menu, dialog panel and overlay card on the PC sits on layer-01,
// and a row inside it on layer-02. They are not part of our canvas skin: a menu is a
// component, and the owner compares components. The canvas stays ours (`Bg #0A0A0A`).
//
// Before this the app drew those panels at #1B1B1F, which is darker than our own composer
// (`Surface #0E0E0E` is only 4 steps away) and 9 steps darker than their #242424, so every
// menu read as a hole rather than a sheet.
//
// Grey removal: their #242424/#2E2E2E were neutral grey sheets floating over our black page -
// that is exactly the "all gray" the owner pointed at. Ours sit one step off the canvas on the ink
// ramp, so a menu still separates from the page without turning grey.
val Layer1 = Color(0xFF1B1B21)
val Layer2 = Color(0xFF232330)

// A modal scrim. Their dialogs dim the page with a plain black wash, not a tinted one; 60% is
// enough to push the composer back without hiding it. Every overlay in the app uses this one
// value so a dialog opened from a dialog does not double-darken differently.
val Scrim = Color(0x99000000)

// Text drawn on top of an `Accent` or other light fill. Their inverse ink is `#080808`
// (`--v2-text-text-inverse`), ours is the canvas black so a filled button matches the page.
val Ink = Color(0xFF0A0A0A)

// the owner, 20 Nov: "i want black and white and our purple not too purple."
// The old accent was `#D0BCFF`, Material 3's stock dark lavender at 100% saturation — it read as
// "purple purple" rather than ours. This keeps the hue (262°) and drops saturation to ~29%, so it
// is a grey-violet that still separates a link from `TextPrimary` (10.4:1 contrast on `Bg`).
val Accent = Color(0xFFB7A8D2)
val AccentSoft = Color(0xFFDDD4EC)
val AccentContainer = Color(0xFF3A3448)

// Links are **not** the accent. Their dark theme maps `--v2-text-text-accent` to `v2-blue-400`
// (`#a2bcff`) and `--v2-text-text-accent-hover` to `v2-blue-300` (`#c3d4fd`); the light theme
// uses `v2-blue-600` (`#3b5cf6`). A prose anchor is `a { color: var(--v2-text-text-accent);
// text-decoration: none }`, so the only thing that marks a link is that blue.
//
// Drawing links in `Accent` was wrong twice: the colour is not theirs, and `#B7A8D2` sits close
// enough to `TextPrimary` on `#0A0A0A` that a link read as plain text. the owner said so directly on
// 21 Nov ("why i dont see highlight of the link colors").
val Link = Color(0xFFA2BCFF)         // --v2-text-text-accent (dark) = v2-blue-400
val LinkHover = Color(0xFFC3D4FD)    // --v2-text-text-accent-hover (dark) = v2-blue-300

// `--v2-text-text-code-path` (dark) = `v2-blue-300`. Their CSS gives a code span that names a
// file its own token and **no background**: `[data-inline-code-kind=path] { color:
// var(--markdown-inline-code-path-color); background-color: #0000 }`. Same hex as `LinkHover`
// by coincidence of the palette, not by meaning - keep them separate.
val CodePath = Color(0xFFC3D4FD)

// `--v2-state-fg-*` (dark block). These are **status** colours, not diff colours: an error line
// under a tool row is `state-fg-danger` (#f17471), while a removed diff line is
// `--text-diff-delete-base` (#fc533a). Two different tokens that look similar and are not.
val Success = Color(0xFF6BD586)   // v2-green-500
val Warning = Color(0xFFF2CF76)   // v2-yellow-500
val Danger = Color(0xFFF17471)    // v2-red-500
val Info = Color(0xFF7698FD)      // v2-blue-500

// `:not(pre)>code { color: var(--v2-text-text-base); background: var(--v2-text-text-base) }`
// with `--markdown-inline-code-bg-mix: 8%` - the chip fill is the **body text colour** at 8%,
// not a fixed grey. Was `0x14A0A0A0`, then `0x14EDEDED`; the body text is `#FAFAFA` now, so
// the fill has to follow it or the chip drifts every time the text token moves.
val InlineCodeBg = Color(0x14FAFAFA)
// Diff colours.
//
// the owner, 2026-11-22: *"i hate the color red gren and gray to the files codes its not a green red
// color like maganda sa mata tignan same sa ibang color"*. This palette used to be byte-identical to
// the PC renderer; it no longer is, and that is a deliberate override of the old rule, not drift.
// The PC values are kept below as the record of what was replaced, because they are what the
// `<diffs-container>` theme in `out/renderer/assets/worker-CBHqm3EI.js` actually ships:
//
//   --diffs-bg                     #151515   --diffs-fg-number                #808080
//   --diffs-bg-addition-override   #198b43 @ 10% -> #1a2919
//   --diffs-bg-deletion-override   #f1484f @ 10% -> #42120b
//   --diffs-fg-number-addition     #96e3a6   --diffs-fg-number-deletion      #f17471
//   addition bar                   #96e3a6   deletion bar                    #f17471
//   separator                      #1e1e1e   --text-diff-add-base            #9bcd97
//   --text-diff-delete-base        #fc533a
//
// That is a traffic light: mint green, fire-engine red, and three flat greys.
//
// **First attempt, and why it was wrong.** The replacement paired the accent violet for additions
// with a warm sand (#D2A66B text, #C0904F rail, #221A13 row) for deletions. On the phone it read as
// brown mud: a screenshot of the real screen sampled the removed row at `#221A13`, a warm brown, and
// the `-N` counter at #C0904F, an orange. the owner, same day: *"tignan mo screen ko ngayon hindi bagay
// yung color sa pinalit mo"*. He was right - on a violet-cast black, the only warm pixel mass on the
// screen was the delete row, so the eye went straight to it and it looked like a stain rather than a
// removal.
//
// **Third attempt, and the one that ships.** the owner posted a five-swatch palette and said
// *"ganito sana and then yung -1 +1 numbers din"*:
//
//     #981C2D  deep red        #0F5E55  deep teal
//     #D62839  red             #2DB6A3  bright teal
//     #C73B59  rose
//
// So the diff is a red/teal pair again, not a grass green. The palette is used **verbatim** where
// a colour is a graphic - the 2px rails and the `-1` / `+1` gutters - and lifted one step where it
// is text, because `#C73B59` tops out at 4.2:1 on pure black and can never reach the 4.5:1 floor
// on a tinted row. `#E8697E` is that same rose lightened, not a new hue.
//
//   added   = #2DB6A3 text on #0E2929   (palette teal, 35% over the canvas, 6.09:1)
//   removed = #E8697E text on #3E121B   (rose lifted from #C73B59, 5.20:1)
//   rails   = #2DB6A3 / #D62839         (palette verbatim - the two bars, and the gutters)
//   context = the ink ramp              (no R=G=B anywhere)
//
// The rows are **opaque fills**, not the PC's 10% alpha washes over #151515 - that wash is what
// read as brown once the canvas moved to violet-black. Both fills are flat (no gradient - the owner,
// 20 Nov) and stay dark enough that the code is the brightest thing in the block.
val DiffAdd = Color(0xFF2DB6A3)      // palette `#2DB6A3` - `+N`, "Added", 6.09:1 on DiffAddBg
val DiffDel = Color(0xFFE8697E)      // `#C73B59` lifted for text - `-N`, "Removed", 5.20:1 on DiffDelBg
val DiffBarAdd = Color(0xFF2DB6A3)   // palette `#2DB6A3` - the addition rail
val DiffBarDel = Color(0xFFD62839)   // palette `#D62839` - the deletion rail
val DiffNum = Color(0xFF7E7C8B)      // was #808080 - unchanged line numbers, 4.68:1 on DiffBg
val DiffBg = Color(0xFF0D0D11)       // was #151515 - the diff canvas
val DiffAddBg = Color(0xFF0E2929)    // solid teal - `#0F5E55` at 35% over DiffBg
val DiffDelBg = Color(0xFF3E121B)    // solid deep red - `#981C2D` at 35% over DiffBg
val DiffHunkBg = Color(0xFF191920)   // was #1E1E1E - the `@@` band, now our `Line`
val DiffHunkText = Color(0xFF848290) // was #808080 - the `@@` band's text, 4.63:1 on DiffHunkBg
val Good = Color(0xFFB7A8D2)
// `Warn` was #A8A8A8 and `Bad` #3C3C42 - the last two neutral greys in the app palette. A warning
// that is grey reads as disabled text, which is the opposite of what it means, so both move onto
// the ink ramp: `Warn` keeps its contrast (8.36:1) and now shares the accent's hue.
val Warn = Color(0xFFA9A6BC)
// `Bad` was #34343E here, and #3C3C42 before that - both darker than the card they sit on, so
// every failure in the app was drawn in a colour you cannot see. `GuardScreen` uses it for the
// route dot, the Doctor "fail" level and `RepairStep.FAILED`; on `#0A0A0A` a #34343E dot is
// invisible, which is the one state that must never be. It takes the red this file already
// defines as `Danger`, so "bad" is red and nothing else in the ramp changes.
val Bad = Color(0xFFF17471)

private val Scheme = darkColorScheme(
    primary = Accent,
    // The M3 defaults for these were violet-tinted (`#371E73`, `#4F378B`, `#EADDFF`). In a
    // black-and-white app the containers should be near-black and the on-colours near-white,
    // or every ripple and chip picks the purple back up.
    onPrimary = Color(0xFF141218),
    primaryContainer = Color(0xFF2A2536),
    onPrimaryContainer = AccentSoft,
    secondary = AccentSoft,
    onSecondary = Color(0xFF141218),
    secondaryContainer = AccentContainer,
    onSecondaryContainer = AccentSoft,
    tertiary = Accent,
    onTertiary = Color(0xFF141218),
    tertiaryContainer = AccentContainer,
    onTertiaryContainer = AccentSoft,
    background = Bg,
    onBackground = TextPrimary,
    surface = Surface,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMid,
    surfaceContainer = Surface,
    surfaceContainerLow = Bg,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface2,
    outline = Border,
    outlineVariant = Line,
    error = TextMid,
    onError = Bg,
)

@Composable
fun ZappTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
