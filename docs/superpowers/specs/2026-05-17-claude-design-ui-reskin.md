# Claude-Design UI Reskin — Design→Compose Mapping Spec

> Date: 2026-05-17. Owner-approved. This is the implementation contract for the
> subagent-driven reskin of the Android `:app` view layer to the user-supplied
> Claude Design bundle. The bundle source is preserved in-repo at
> `docs/superpowers/design/2026-05-17-tv-remote-claude-design/` — implementers
> and reviewers MUST diff exact hex/dimensions against `project/remote.jsx`,
> `project/connect.jsx`, `project/icons.jsx`. A rough approximation is not
> pixel fidelity.

## Scope & invariants

- **View + nav layer only.** All ViewModels, `ConnectionManager`,
  `ConnectionService`, `CredentialStore`, `MulticastLockHolder`, `Haptics`,
  `AppGraph`, and the entire `:protocol`/`:trace-tools` modules are **logic-
  locked** — not modified except to *call* their existing public APIs. `Api.kt`
  and all Plan-1/Plan-2 golden tests stay byte-identical.
- The reskin **replaces** the S4/S5 screens (`HeroScreen`, `Trackpad`,
  `DpadRow`, `ButtonRow`, `DevicesScreen`, `PairScreen`, `KeyboardScreen`) and
  restructures `AppNav`/`MainActivity` nav wiring. The S1–S5 *connection /
  pair / auto-reconnect / Service-scope / multicast* logic is preserved and
  re-targeted to the new screens — **not** rewritten.
- Per-task commits to `main`. **Do NOT push** (owner-gated).
- The old UI tests pin the *old* design; the user directed this reskin, so the
  affected UI tests are **rewritten to pin the new design** (TDD: new spec →
  new tests). Logic/VM/data tests stay green unchanged.

## Two locked reconciliation decisions (owner-approved)

1. **Keep the design's exact look/spacing/components; drive from the real VMs;
   gracefully degrade protocol-unavailable data.** Specifically:
   - **RSSI signal bars** — protocol has no RSSI. *Drop the bars.* Right edge
     of a device card = `IconCheck` (current) else `IconChevron`. Add a small
     JetBrains-Mono `已配对` chip when `DiscoveredDevice.paired` is true (real
     data we *do* have), styled like the `CURRENT` badge but neutral.
   - **Wi-Fi status pill** — show the real SSID if obtainable via `WifiManager`
     (already have `CHANGE_WIFI_MULTICAST_STATE`; reading SSID needs no extra
     dangerous permission on the connected network for our use — if it returns
     `<unknown ssid>`/null, degrade the title to `已连接` and hide the IP
     subline). Spinner vs refresh icon is driven by real
     `DiscoveryUiState.scanning`.
   - **Scan animation / "已发现 N"** — driven by real `DiscoveryUiState`
     (`devices.size`, `scanning`). Discovery is continuous (no restart API);
     the refresh affordance shown when `!scanning` is a visual affordance that
     re-emits nothing destructive (no-op tap is acceptable; do not fabricate a
     fake re-scan).
   - **Settings gear** (Connect top-bar right) → opens the existing
     `SwipeTuningScreen` (the only "settings/debug" surface). Not invented UI.
   - **Manual add IP** — keep the prominent button; on tap show a minimal
     numeric IP + name dialog that constructs an `AppleTvDevice` and feeds the
     **same** `onSelectDevice` path (pair-or-connect). Minimal, not a new flow.
2. **4 fixed digit boxes, per-digit auto-advance.** The Companion PIN is 4
   digits. `PairingSheet` renders 4 boxes; typing a digit advances focus;
   backspace on an empty box retreats; when all 4 are filled, submit the
   concatenated string via `PairingViewModel.submitPin(code)`. No single
   free-text field.

## Design tokens (lift exact — from the in-repo bundle)

Theme is **dark-only** (the design has no light variant). Replace the
dynamic/light/dark Material scheme with a single fixed dark scheme + explicit
brushes. Status/navigation bars transparent (already so in `themes.xml`).

| Token | Value | Source |
|---|---|---|
| Remote screen bg | `radial-gradient(120% 80% at 50% 0%, #181c25 0%, #0c0e13 60%, #08090c 100%)` | remote.jsx:289 |
| Connect screen bg | solid `#0E1014` | connect.jsx:128 |
| Surface card | `#16181D` | connect.jsx:219 |
| Inset field bg | `#0E1014` | connect.jsx:83 |
| Sheet bg | `#16181D` | connect.jsx:54 |
| Text primary | `#FFFFFF` / `#E9EAEE` | bundle |
| Text on-muted | `rgba(255,255,255,0.55)` / `0.5` / `0.45` / `0.35` | connect.jsx |
| Accent blue | `#5B89FF` | bundle |
| Accent light | `#8FB8FF` (eyebrow/icons) / `#CFDAFF` (active text) | bundle |
| Green dot | `#3ECF8E` | connect.jsx:243 |
| TV-logo gradient | `linear-gradient(135deg, #4A72FF, #2C4ED4)` | connect.jsx:147 |
| Touchpad disk | `radial-gradient(circle at 35% 30%, #2A2E38 0%, #181A20 60%, #0F1115 100%)` | remote.jsx:63 |
| Touchpad ring glow | `radial-gradient(closest-side, rgba(91,137,255,0.18), transparent 70%)` | remote.jsx:51 |
| Center OK idle | `radial-gradient(circle at 40% 35%, #1F232B, #0D1015)` | remote.jsx:117 |
| Center OK active | `radial-gradient(circle at 40% 35%, #2C3956, #1A2236)` + blue inset glow | remote.jsx:116,119 |
| Round button idle | `radial-gradient(circle at 35% 30%, #232730, #14171D)` | remote.jsx:142 |
| Round button active | `radial-gradient(circle at 35% 30%, #2C3956, #1A2236)`, text `#CFDAFF`, scale .95, blue glow | remote.jsx:141,148 |
| Dir glow on press | `radial-gradient(ellipse 80% 60% at <edge>, rgba(91,137,255,0.55), transparent 60%)` | remote.jsx:93-96 |
| Fonts | UI = Inter; mono (eyebrows/codes/badges/ip) = JetBrains Mono | bundle |

**Dimensions:** Touchpad 240dp; outer ring inset −10dp; center OK 96dp; OK
inner radius hit-test = `width*0.18`; round button 80dp; VolumePill 80×172dp
radius 40dp; device card radius 16dp pad 14×16dp; card TV-tile 44dp radius
12dp; pairing box 64dp tall radius 14dp gap 10dp border 1.5dp; sheet radius
`24dp 24dp 0 0`; status pill radius 12dp pad 12×14dp; manual-add 52dp radius
14dp; grip 40×4dp.

**Fonts:** bundle `Inter` + `JetBrainsMono` into `app/src/main/res/font/`
(static `.ttf`, weights 400/500/600/700) and wire a Compose `FontFamily`.
Graceful degradation: if a weight is unavailable use `FontFamily.Default`
(UI) / `FontFamily.Monospace` (mono) — never crash on a missing resource.

**Press timing:** directional/OK active visual ~180ms (remote.jsx:12);
button press scale `.95` transition ~100ms; glow ~150ms; sheet slide-up
~300ms `cubic-bezier(.2,.7,.2,1)`; overlay fade ~200ms.

## Screen 1 — RemoteScreen (replaces HeroScreen + Trackpad/DpadRow/ButtonRow)

Source: `project/remote.jsx`. Background = Remote radial gradient. Column.

**Top bar** (remote.jsx:294): centered device-switcher button — `device.name`
(15sp, 600) + small chevron-down (`rgba(255,255,255,0.5)`), tap →
`onSwitchDevice()` (opens Connect-as-overlay). Right: 32dp circle power button
bg `rgba(255,255,255,0.06)`, `IconPower` 16dp — **tap = `vm.wake()`,
long-press = `vm.sleep()`** (preserve S4 power semantics; the prototype's
`cmd()` is silent — the real mapping is the graceful upgrade). No back button,
no left avatar (final design).

**Center** (remote.jsx:323): `Touchpad`, then the 3×2 grid, vertically
centered, gap 28dp, padding `12dp 20dp 24dp`.

**Touchpad** — see §primitives. Interaction contract (reconciliation):
- **Discrete tap** is hit-tested with the design's *exact* math
  (remote.jsx:15-34): center `r < width*0.18` → `vm.pressButton(Select)`;
  else `atan2(y,x)` in deg → `[-45,45)`=Right, `[45,135)`=Down,
  `[-135,-45)`=Up, else Left → `vm.pressButton(Up/Down/Left/Right)`.
- **Drag** continues to feed the **existing `SwipeEngine` pointerInput →
  `RemoteViewModel.onTouchEvent`** (continuous Siri-remote-equivalent touch is
  a real capability — preserved, a graceful enhancement over the static
  prototype, never a regression). A movement that exceeds touch-slop is a
  drag (SwipeEngine path) and is **not** also fired as a tap-zone press.
- Visual state (active dot, edge glow, center-OK scale+glow ~180ms) is driven
  by whichever path fired (tap-zone dir, or `SwipeEngine` `DirectionalStep`/
  `Tap`). `testTag("trackpad")` retained.

**3×2 grid** (remote.jsx:327, `gridTemplateColumns:1fr 1fr`, rowGap 12,
padding `0 12dp`, items centered):
| | Col 1 | Col 2 |
|---|---|---|
| Row 1 | `IconBack` → `vm.menu()` (cd "Back") | `IconTV` → `vm.home()` (cd "TV/Home") |
| Row 2 | `IconPlayPause` → `vm.playPause()` (cd "Play/Pause") | `VolumePill` (gridRow 2/span 2, gridColumn 2) |
| Row 3 | `IconKeyboard` → open KeyboardOverlay (cd "Keyboard") | ↑(pill spans) |

`VolumePill` top half `IconPlus` → `vm.volumeUp()` (cd "Volume Up"), bottom
half `IconMinus` → `vm.volumeDown()` (cd "Volume Down"). **No labels, no
toasts** (final design). `contentDescription`s above are required for a11y +
the rewritten UI tests.

**Keyboard gating:** the Keyboard button is gated by the existing
`keyboardProbe`/`keyboardAvailable` (Plan-2 T16 keyboard members are
`NotImplementedError` stubs) — when unavailable the button is **disabled**
(dimmed, not removed; keeps the grid layout). Consistent with current HeroScreen.

**KeyboardOverlay** (remote.jsx:232): full-screen `rgba(8,9,12,0.85)` + blur
(Compose: scrim color + `RenderEffect.createBlurEffect` API≥31, else solid
`#0B0C10` — name the tradeoff). Header eyebrow `TEXT INPUT → {device.name}`
(mono 11sp `#8FB8FF`) + `完成` pill button (closes). Input box bg `#0E1014`
border `rgba(91,137,255,0.3)` radius 14dp minHeight 120dp, single text field
18sp white, placeholder `在此输入要发送到电视的文本...`. Counter line
`{n} chars · 实时发送到 Apple TV` mono 11sp. Bound to `KeyboardViewModel`:
field value = `state.text`, edits → `keyboardVm.setText(text)` (real-time
send). Opened by the Keyboard grid button; **also** auto-shown when
`KeyboardViewModel.state.visible` becomes true (ATV focused a field) — preserve
the existing auto-route behavior, just rendered as an in-Remote overlay.

## Screen 2 — ConnectScreen + PairingSheet (replaces DevicesScreen + PairScreen)

Source: `project/connect.jsx`. Two modes via a `currentId: String?` +
`onClose: (() -> Unit)?`:
- **First-run mode** (`onClose == null`): TV-logo gradient tile + title
  `TV Remote`, eyebrow `STEP 01 — DISCOVER`, hero `在 Wi-Fi 网络上 / 寻找你的
  Apple TV`. No close.
- **Switcher overlay mode** (`onClose != null`, reached from Remote chip):
  back button + title `切换设备`, eyebrow `SWITCH — SELECT DEVICE`, hero
  `选择要控制的 / Apple TV 设备`. The currently-connected device card shows
  `CURRENT` badge + `IconCheck` + accent border; tapping it = `onClose()`.

Top-bar right = settings gear → open `SwipeTuningScreen`.

**Status pill** (connect.jsx:182): `IconWifi` `#8FB8FF`, title = real SSID
(degrade → `已连接`), subline real `192.168.x.x · 已连接` (degrade → hidden),
trailing = `IconSpinner` (real `state.scanning`) else `IconRefresh` button.

**Device list** (connect.jsx:206): eyebrow `搜索中... 已发现 N` /
`已发现 N 台设备` from real state. Each `DiscoveredDevice` → card: 44dp TV-tile,
`device.name` (15sp 600) + green `IconDot` + optional `CURRENT` badge +
optional `已配对` chip (`paired`), `model` line (degrade: if model null show
host), `host` mono line, right = `IconCheck`(current)/`IconChevron`. **No RSSI
bars.** Scanning placeholder dashed row while `scanning`. Tap: current →
`onClose()`; else → `onSelectDevice(dd)` (MainActivity decides connect-if-
paired vs PairingSheet — keep S5 logic).

**Manual add IP** (connect.jsx:284): button `+ 手动添加 IP 地址` → minimal
dialog (name optional, IP required numeric+dots, default port 49153) →
constructs `AppleTvDevice` → `onSelectDevice`. Footer hint static
(connect.jsx:303).

**PairingSheet** (connect.jsx:24): bottom sheet, scrim `rgba(8,9,12,0.72)`
+ blur, sheet `#16181D` radius `24 24 0 0`, slide-up. Grip; eyebrow `配对设备`;
`{device.name}` 22sp 600; `请输入电视屏幕上显示的 4 位配对码`. **4 boxes**
64dp, border `#5B89FF` when filled else `rgba(255,255,255,0.10)`, 28sp 600
mono, per-digit auto-advance (decision 2). `取消` button = cancel.

Driven by `PairingViewModel` (`state: StateFlow<PairingUiState>`):
- `Connecting` → boxes disabled + inline spinner near the title.
- `AwaitingPin` → boxes enabled & focused; all-4-filled → `submitPin(code)`.
- `Completed` → `onPaired(device)` (MainActivity connects + closes sheet).
- `Failed(reason)` → show `reason` in the sheet, clear boxes, re-enable for
  retry; `取消` cancels. (Prototype had no Failed — this is the real-state
  graceful addition.)

## Screen 3 — Nav flow (AppNav + MainActivity)

Design `app.jsx`: no paired/auto → ConnectScreen → onConnected → RemoteScreen;
else RemoteScreen; Remote chip → ConnectScreen overlay → select → (PairingSheet
or switch) → back to Remote. Remote has **no back button**.

**`AppDestinations`** restructured (the old `[HERO,DEVICES,PAIR,KEYBOARD,
TUNING]` pins are superseded by the design). New model:
- `REMOTE("remote")` — RemoteScreen; KeyboardOverlay & device-switcher are
  *overlays/secondary*, not separate destinations.
- `CONNECT("connect")` — ConnectScreen (first-run + overlay modes);
  PairingSheet is an overlay within it.
- `TUNING("tuning")` — debug, reached only from the Connect settings gear.

`initialDestination(hasReconnectableLast)` → `REMOTE` if true else `CONNECT`.
`NavRequest(dest, seq)` retained. The MulticastLock `DisposableEffect` moves
to the `CONNECT` branch (discovery now lives there) — keep
acquire/onDispose-release.

**MainActivity**: keep S5 verbatim in behavior — Service bind,
`serviceScope`/`launchConnect`, `launchConnectGuarded`, `LaunchedEffect(cm)`
auto-reconnect via `credentialStore.lastDevice()`, `ActivePairing(vm,device)`,
`pendingSelect`, terminal-state handling (Completed→connect+saveLastDevice;
Failed→stay; Cancel→back), `initialDevices` gating render. Only the *targets*
change: `onSelectDevice` still sets `pendingSelect`; PAIR is now the
PairingSheet inside CONNECT (pass `pairingState`/`onSubmitPin`/`onPairCancel`
down); HERO→REMOTE; DEVICES→CONNECT; the Remote device-switcher chip issues
`navigateTo(CONNECT)` with `currentId` = connected device id; selecting the
current device or back closes the overlay → `navigateTo(REMOTE)`.

## Tests (rewrite to pin the NEW design; logic tests unchanged)

Rewrite: `AppNavTest` (new `AppDestinations` = `[REMOTE,CONNECT,TUNING]`),
`AppNavRoutingTest` (`initialDestination` REMOTE/CONNECT), `AppNavUiTest`
(first-run→CONNECT, requested REMOTE, multicastLock held on CONNECT),
`HeroScreenUiTest`→`RemoteScreenUiTest` (testTag "trackpad"; cd Back/TV-Home/
Play-Pause/Volume Up/Volume Down/Keyboard/Power; device chip; no D-pad/Mute/
Siri/Apps; touchpad tap-zone → `pressButton` mapping),
`PairScreenUiTest`→`PairingSheetUiTest` (4 boxes, auto-advance, all-filled→
submitPin, Failed shows reason), `KeyboardScreenUiTest`→`KeyboardOverlayUiTest`
(overlay header/counter, edits → setText). New: `TouchpadZoneTest` (the
atan2/0.18r math → direction). Keep green unchanged: every `vm/`, `data/`,
`conn/`, `swipe/`, `haptics/` test (logic-locked).

## Task breakdown (subagent-driven; per-task commit to `main`, NOT pushed)

- **T1 — Design system foundation:** `res/font/` Inter+JetBrainsMono +
  Compose `FontFamily`; rewrite `theme/Color.kt` (token object) + `Theme.kt`
  (fixed dark scheme) + `Type.kt`; add a `theme/Brushes.kt` (the radial/linear
  gradient + glow brush helpers). No behavior. Theme tests (if any) updated.
- **T2 — Remote primitives:** `Touchpad`, `RemoteButton`, `VolumePill`
  (design visuals + the tap-zone math + preserved SwipeEngine drag path) +
  `TouchpadZoneTest`. Replaces Trackpad/DpadRow/ButtonRow.
- **T3 — RemoteScreen:** assemble top bar/power/grid/VolumePill/
  KeyboardOverlay, wire to `RemoteViewModel`/`KeyboardViewModel`, keyboard
  gating; `RemoteScreenUiTest` + `KeyboardOverlayUiTest`. Replaces HeroScreen.
- **T4 — ConnectScreen + PairingSheet:** both modes, status pill (degraded),
  device cards (degraded), manual-add dialog, footer, 4-box auto-advance
  PairingSheet wired to `PairingViewModel`; `ConnectScreenUiTest` +
  `PairingSheetUiTest`. Replaces DevicesScreen/PairScreen.
- **T5 — Nav + MainActivity wiring:** restructure `AppNav`/`AppDestinations`/
  `initialDestination`, re-target `MainActivity` (S5 logic intact), multicast
  on CONNECT, settings-gear→Tuning; rewrite `AppNavTest`/`AppNavRoutingTest`/
  `AppNavUiTest`. Capstone — full clean `:app:testDebugUnitTest` green.

Each task: implementer → spec-compliance review (vs this doc + the bundle
JSX) → code-quality review → fix loops → commit. Final whole-app review +
clean-build green gate before handoff.
