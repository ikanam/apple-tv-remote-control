# First-Run Connect/Pair + Hero D-pad — Design

> Spec date: 2026-05-17. Scope: `:app` only. `:protocol` and `:trace-tools` are
> LOCKED and not modified (everything needed is already in the LOCKED public API).

## Problem

The Plan-3 app builds and launches (the prior launch-crash — missing Android-14
`connectedDevice` FGS prerequisite permission — is fixed and confirmed on
device), but on a fresh install it is **unusable**:

- `AppNav` starts on `HERO`; `ConnectionManager` is `Idle`; nothing calls
  `connect()`. You see a placeholder "Apple TV" Hero with no prompt.
- `MainActivity.onSelectDevice` only does `credentialStore.load(id) ?: return`
  — on a fresh install there are no stored credentials, so it returns and never
  connects.
- `pairingState = null`, `onSubmitPin = {}`, nothing ever routes to `PAIR`.
  `AppleTvRemote.pair(device)` / `PairingViewModel` are wired **nowhere**. There
  is no code path to pair an unpaired Apple TV (base-T15's "Devices→Pair
  binding" was an aspirational prose note never coded — flagged in the Plan-3
  final-review known-limitations register, item d).
- The Companion protocol discovers via jmDNS (pure-JVM `:protocol`). On Android
  Wi-Fi, multicast responses are filtered unless the app holds a
  `WifiManager.MulticastLock`; `:protocol` (no Android deps) cannot take one, so
  the device list would be empty even if pairing were wired.
- The first `connect()` is issued from `MainActivity.lifecycleScope.launchWhenStarted`
  (deprecated API; contradicts `ConnectionManager`'s documented "first connect()
  must run on a long-lived scope" contract — Activity recreate/background drops
  the connection).

Additionally, real-device feedback: the Hero is missing **directional and
confirm buttons**. The owner-approved 2026-05-16 remote-layout amendment made
Hero a 1:1 physical Siri Remote (no D-pad/Select; trackpad does directional via
swipe/edge-zones, tap = Select). In practice the trackpad is invisible
(dark-on-dark) with no "tap = OK" affordance.

## Spec amendment (owner-directed, supersedes 2026-05-16)

Per the owner's real-device direction, this spec **supersedes** the relevant part
of `docs/superpowers/specs/2026-05-16-plan3-remote-layout-design.md` /
`docs/superpowers/plans/2026-05-16-plan3-remote-layout-amendment.md`: the Hero
gains a **visibly-bordered trackpad PLUS an explicit D-pad (Up/Down/Left/Right)
and a center OK/Select button**. Trackpad swipe/edge-zone directional behavior is
retained (not removed); the D-pad/OK are added alongside it. User instruction
takes precedence over the earlier owner-approved layout decision.

## Goals

A fresh install can: launch → (auto-reconnect last device if known) → discover
Apple TVs on the LAN → pair an unpaired one via on-TV PIN → persist credentials →
connect → control via Hero (including explicit D-pad + OK). The connection
survives Activity recreation/backgrounding.

Non-goals: real-device validation (still owed per
`docs/PLAN-2-DEVICE-SESSION-RUNBOOK.md`; this is code-first), multi-device
management UI beyond "last device", any `:protocol` change.

## Design

### 1. Connection lifecycle → ConnectionService scope

`ConnectionService` (currently `Service`) gains a service-lifetime scope:
`private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`,
cancelled in `onDestroy()` (added before the existing `super.onDestroy()` body /
runCatching, not altering the FGS/binder behavior). `LocalBinder` gains:

- `fun launchConnect(device: AppleTvDevice, creds: HapCredentials)` →
  `serviceScope.launch { connectionManager.connect(device, creds) }`
- `fun launchPair(...)` is **not** needed on the binder — pairing's
  `PairingHandle` flow is collected by `PairingViewModel` on its own
  `viewModelScope`; only the final `connect()` after a successful pair goes
  through `launchConnect`. (Keeps the binder surface minimal.)

`MainActivity` replaces every `lifecycleScope.launchWhenStarted { cm.connect(...) }`
with `binder.launchConnect(...)`. This satisfies `ConnectionManager`'s T6 KDoc
contract (the observer/reconnect work scope parents to the Service's long-lived
job) and removes the deprecated `launchWhenStarted`.

### 2. "Last device" persistence + auto-reconnect

`CredentialStore` gains two methods on its existing encrypted DataStore (the
blob is Keystore-sealed like the credentials, keyed by a fixed key):

- `suspend fun saveLastDevice(device: AppleTvDevice)` — serialize
  `idnamehostportmodelpairable` (model may be null →
  empty). The 6 AppleTvDevice fields (id, name, host, port, model nullable,
  pairable) MUST use a collision-safe per-field encoding (the device name can
  contain any naive delimiter; use per-field URL/Base64 or a JSON object, NOT a
  plain pipe/comma-delimited string), then seal via the existing
  `KeystoreCipher`.
- `suspend fun lastDevice(): AppleTvDevice?` — unseal + parse, or null.

`saveLastDevice` is called whenever a device successfully connects and whenever
pairing completes (alongside `save(id, blob)`).

Launch flow in `MainActivity` (after the service is bound, inside the existing
`isReady`/`cm` gate, in a `LaunchedEffect(cm)` so it runs once):

1. `val last = credentialStore.lastDevice()`; if `last != null` and
   `credentialStore.load(last.id) != null` → `binder.launchConnect(last,
   HapCredentials.parse(blob))`. AppNav stays on `HERO`; the
   `Connecting`/`Connected`/`Reconnecting` banner reflects progress. On
   `CredentialInvalid`/`Failed` the existing AppNav banner shows, and the user
   can open Devices to re-pair.
2. If no last device or no creds → AppNav initial `dest = DEVICES` (see §3) so
   the user lands on the device list, not a dead Hero.

Auto-reconnect uses the persisted full `AppleTvDevice` (host/port included), so
it does **not** depend on mDNS finding the device first — robust even if
multicast is flaky.

### 3. Pairing flow wiring (the unwired gap)

`AppNav` initial destination becomes a function of connection state: if
`connectionState` is `Idle` AND no auto-reconnect is in flight → start at
`DEVICES`; otherwise `HERO` (existing auto-route logic for KEYBOARD unchanged).
Concretely AppNav gains an `initialDevices: Boolean` parameter (computed by
MainActivity = "no last device/creds"); `var dest by remember {
mutableStateOf(if (initialDevices) DEVICES else HERO) }`.

`DevicesScreen` `onSelect(device)` (wired via a new `onSelectDevice(device:
DiscoveredDevice)` — AppNav passes the full `DiscoveredDevice`, not just id, so
the device's host/port is available without re-lookup):

- Paired (`credentialStore.load(device.id) != null`): `binder.launchConnect`,
  `saveLastDevice`, `dest = HERO`.
- Unpaired: MainActivity creates
  `PairingViewModel(deviceId = device.id, handle = AppleTvRemote.pair(device),
  persist = { id, blob -> credentialStore.save(id, blob);
  credentialStore.saveLastDevice(device) })`, holds it in a
  `mutableStateOf<PairingViewModel?>`, sets `dest = PAIR`. AppNav's `PAIR`
  branch is fed `pairingState = pairingVm?.state.collectAsState()` and
  `onSubmitPin = { pairingVm?.submitPin(it) }`. On `PairingUiState.Completed`
  → `binder.launchConnect(device, HapCredentials.parse(credentialStore.load(device.id)!!))`,
  `dest = HERO`, clear the held `PairingViewModel`. `PairScreen`'s existing
  `onCancel` → `pairingVm?.cancel()`, `dest = DEVICES`.

This reuses the already-built+tested `PairingViewModel` (P3-T9) and `PairScreen`
(P3-T15) verbatim — only the MainActivity/AppNav glue is added.

### 4. jmDNS Wi-Fi multicast lock

New `app/.../conn/MulticastLockHolder.kt`: wraps
`WifiManager.createMulticastLock("atvremote-mdns")`; `acquire()` (idempotent,
ref-counted or guarded) / `release()`. Constructed in `AppGraph` (process-wide,
`by lazy`, from the application `Context`'s `WifiManager`). The Devices screen
(AppNav `DEVICES` branch) wraps its content in
`DisposableEffect(Unit) { holder.acquire(); onDispose { holder.release() } }` so
the lock is held only while discovering — battery-correct. Discovery itself is
already started by `DiscoveryViewModel` collecting `AppleTvRemote.discovery()`;
the lock just makes the OS deliver multicast replies to jmDNS while that screen
is up. Requires `CHANGE_WIFI_MULTICAST_STATE` (already added to the manifest in
the launch-crash fix).

### 5. Hero: visible trackpad + D-pad + OK

- `Trackpad.kt`: add a visible boundary — `.border(1.dp,
  MaterialTheme.colorScheme.outline, CircleShape)` (or a contrasting surface
  tint) so the circular trackpad is discoverable. No behavior change (still
  funnels into `SwipeEngine`).
- New `app/.../ui/hero/DpadRow.kt`: a directional cluster — Up / Down / Left /
  Right `IconButton`s arranged as a cross with a center **OK** button.
  `contentDescription`s: `"Up"`, `"Down"`, `"Left"`, `"Right"`, `"Select"`.
  Callbacks `onUp/onDown/onLeft/onRight/onSelect: () -> Unit`.
- `HeroScreen.kt`: place `DpadRow` between the `Trackpad` and the existing
  Back·TV/Home row; wire `onUp = { vm.pressButton(RemoteButton.Up) }` … `onSelect
  = { vm.pressButton(RemoteButton.Select) }` (all values exist in the LOCKED
  `RemoteButton` enum: Up=1,Down=2,Left=3,Right=4,Select=6; `RemoteViewModel.
  pressButton` already wired & tested). Existing Power / Back / TV-Home /
  Play-Pause / Volume / Keyboard layout and wiring unchanged.

## Components / boundaries

| Unit | Responsibility | Depends on |
|---|---|---|
| `ConnectionService` (+`LocalBinder.launchConnect`, `serviceScope`) | long-lived owner; runs connect on a service-lifetime scope | `ConnectionManager` |
| `CredentialStore` (+`saveLastDevice`/`lastDevice`) | persist last `AppleTvDevice` (Keystore-sealed) | `KeystoreCipher`, DataStore |
| `MulticastLockHolder` | hold/release Wi-Fi multicast lock | Android `WifiManager` |
| `AppGraph` (+`multicastLock`) | process-wide singletons | the above |
| `DpadRow` (new Composable) | D-pad + OK buttons | `RemoteButton` callbacks |
| `MainActivity`/`AppNav` (glue) | first-run/auto-reconnect routing + pairing wiring | all of the above + `PairingViewModel`/`DiscoveryViewModel` |

## Error handling

- Auto-reconnect failure (`CredentialInvalid`/`Failed`) → existing AppNav banner;
  user opens Devices to re-pair. No crash; `binder.launchConnect` is
  fire-and-forget on the Service scope; `ConnectionManager` already handles
  cred-invalid (clears creds → `CredentialInvalid`).
- Pairing `Failed` → `PairScreen` shows `reason`, back to Devices.
- Multicast lock acquire failure (no Wi-Fi / permission edge) →
  `runCatching` in the holder; discovery degrades to whatever the OS delivers
  (no crash). `saveLastDevice`/`lastDevice` parse failure → returns null
  (treated as "no last device"), `runCatching` like the existing
  `CredentialStore.load`.

## Testing (all JVM/Robolectric — no emulator)

- `CredentialStoreTest`: extend — `saveLastDevice`→`lastDevice` round-trip
  (incl. null `model`), ciphertext-not-plaintext, missing → null.
- `ConnectionServiceTest`: `launchConnect` invokes `ConnectionManager.connect`
  on the service scope (FakeSession connector); `serviceScope` cancelled on
  `onDestroy`. (Robolectric service.)
- New `MulticastLockHolderTest`: acquire/release idempotent against
  Robolectric's `ShadowWifiManager` (lock held flag).
- `HeroScreenUiTest`: extend — assert `Up/Down/Left/Right/Select` nodes exist
  (plus the existing Power/Back/TV-Home/Play-Pause/Volume/Keyboard, no
  Mute/Siri/Apps). New `RemoteViewModelTest` case: D-pad/OK → `pressButton`
  emits the right `RemoteButton` down/up pairs (mirrors existing button test).
- Pairing/auto-reconnect glue: a Robolectric/coroutine test of the AppNav
  routing decisions where unit-testable (initial `DEVICES` vs `HERO`; PAIR
  branch fed a fake `PairingViewModel`). MainActivity itself is thin glue;
  prefer testing the decision logic factored into a small pure
  function/`AppNav` parameters rather than the Activity.
- Full `:app:testDebugUnitTest` green; `:protocol`/`:trace-tools` untouched
  (compile-sanity only).

## Out of scope / residual (owner/device-session owned)

- Real-device pair/connect/Hero validation per `PLAN-2-DEVICE-SESSION-RUNBOOK.md`.
- Power long-press Sleep+Wake Compose gesture-arbitration nuance (separate
  device-session check; unchanged here).
- MainActivity VMs constructed in `setContent` (base-T15 characteristic) — this
  spec wires pairing/reconnect but does not re-architect VM ownership beyond
  what's needed (the held `PairingViewModel` is a deliberate exception).
- Multi-device picker / "forget device" UI.
