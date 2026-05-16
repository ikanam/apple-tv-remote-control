# Plan-3 Remote Layout Amendment — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Amend the existing Plan-3 Android UI to the owner-approved physical-Siri-Remote layout: remove the app-launcher subsystem, relocate Power to the Hero screen (tap=Wake / long-press=Sleep), and put a Keyboard key in the physical Mute slot gated behind the deferred keyboard chain.

**Architecture:** This **amends** `docs/superpowers/plans/2026-05-15-atv-remote-plan-3-android-app-ui.md` (the "base plan", Tasks 1–15) per `docs/superpowers/specs/2026-05-16-plan3-remote-layout-design.md`. All base-plan tasks/files/code stand **unchanged except** the precise deltas in this document. `:protocol` is LOCKED and not modified. Only the `:app` module is touched.

**Tech Stack:** Unchanged from base plan — Kotlin 2.0.21, Jetpack Compose + Material3, kotlinx-coroutines/StateFlow, AndroidX Lifecycle, JUnit5/kotlin-test/Robolectric, `androidx.compose.ui.test`.

---

## Relationship to the base plan (read first)

Execute the base plan's Tasks 1–11, 13, 14 **as written**, with these amendments applied:

- **Base Task 12 (`LauncherViewModel`) — DELETED.** Do not implement it. Skip entirely.
- **Base Task 15** — implement as written **except** the Launcher screen and its `AppNav` route are removed (Amendment Task A2).
- **Base Task 10 (`RemoteViewModel`)** — implement as written **plus** the Power additions in Amendment Task B.
- **Base Task 14 (Hero Composables)** — implement as written **plus** the Hero layout/Power/Keyboard-key changes in Amendment Tasks B & C; the base Task-14 "keyboard entry" affordance is realized as the bottom-left Keyboard key per Task C.
- **Base Task 2** already defines `TouchEvent.DirectionalStep(RemoteButton)` and edge-zone→directional mapping — spec Decision 3 (directional nav via HID) is **already satisfied**; no amendment.

Execution order: base Tasks 1–11, 13 → Amendment Task A → base Task 14 (with Amendment Tasks B, C, D folded in) → base Task 15 (amended). The base plan's `:protocol` symbols and suspend/non-suspend contract (its lines 18–20) are authoritative and unchanged.

## Prerequisite: deferred keyboard chain (Plan-2 T15/T16/T19)

The Keyboard key’s target screen (base Task 11 `KeyboardViewModel` + base Task 15 `KeyboardScreen`) calls `:protocol` `textGet/Set/Clear/Append` + `keyboardFocus`, which are `NotImplementedError` stubs until the **deferred Plan-2 keyboard chain T15/T16/T19** (specified in `docs/superpowers/plans/2026-05-15-atv-remote-plan-2-companion-commands.md`; real `_tiD`/`_tiC` capture data committed in `cc2aa10`: `keyed-archiver-tiD.json`, `text-set.json`).

**Hard ordering:** base Task 11 + the `KeyboardScreen`/Keyboard-key *enabled* path MUST NOT be executed until T15/T16/T19 are complete and `:protocol:test` is green with zero `NotImplementedError`. Until then the Keyboard key is rendered **disabled** via the capability gate in Amendment Task C (this is a build-order state, not a permanent dead button — consistent with the spec). Amendment Tasks A, B and the disabled-key path in C have **no** dependency on the keyboard chain and can proceed immediately.

## File Structure delta (vs base plan)

Removed from the base plan's File Structure:
- `app/src/main/kotlin/dev/atvremote/app/ui/launcher/AppLauncherScreen.kt` — not created
- `app/src/main/kotlin/dev/atvremote/app/vm/LauncherViewModel.kt` — not created
- `app/src/test/kotlin/dev/atvremote/app/vm/LauncherViewModelTest.kt` — not created

Changed responsibilities:
- `vm/RemoteViewModel.kt` — gains `wake()` / `sleep()` (Power moved off the deleted launcher).
- `ui/hero/HeroScreen.kt` — Power control in the top bar; bottom-left Keyboard key; layout mirrors the physical Siri Remote.
- `ui/AppNav.kt` — no `Launcher` destination; navigation is Hero/Devices/Pair/Keyboard/Tuning only.
- `ui/hero/KeyboardCapability.kt` — **new**, tiny pure gate (see Task C).
- `app/src/androidTest/.../ui/HeroScreenUiTest.kt` — adds "no launcher entry / no Mute / no Siri" guard + Power + Keyboard-key assertions.

---

## Amendment Task A: Remove the app-launcher subsystem

**Files:**
- Skip (do not create): `app/src/main/kotlin/dev/atvremote/app/vm/LauncherViewModel.kt`, `app/src/test/kotlin/dev/atvremote/app/vm/LauncherViewModelTest.kt`, `app/src/main/kotlin/dev/atvremote/app/ui/launcher/AppLauncherScreen.kt`
- Modify: `app/src/main/kotlin/dev/atvremote/app/ui/AppNav.kt`
- Test: `app/src/test/kotlin/dev/atvremote/app/ui/AppNavTest.kt`

- [ ] **A1 — Confirm skips.** Base Task 12 is not executed. When executing base Task 15, do not create `AppLauncherScreen.kt`; when executing base Task 1's `:app` File Structure, omit the three files above. No code.

- [ ] **A2 — Write the failing AppNav test (no Launcher route)**

```kotlin
// app/src/test/kotlin/dev/atvremote/app/ui/AppNavTest.kt
package dev.atvremote.app.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppNavTest {
    @Test fun navHasNoLauncherDestination() {
        val routes = AppDestinations.entries.map { it.route }
        assertFalse(routes.any { it.contains("launcher", ignoreCase = true) })
        assertEquals(
            listOf("hero", "devices", "pair", "keyboard", "tuning"),
            routes,
        )
    }
}
```

- [ ] **A3 — Run, expect FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.ui.AppNavTest`
Expected: FAIL — `AppDestinations` has a `LAUNCHER` entry (base plan) or is unresolved.

- [ ] **A4 — Implement: AppNav without Launcher**

In `app/src/main/kotlin/dev/atvremote/app/ui/AppNav.kt`, the destinations enum is exactly:

```kotlin
enum class AppDestinations(val route: String) {
    HERO("hero"),
    DEVICES("devices"),
    PAIR("pair"),
    KEYBOARD("keyboard"),
    TUNING("tuning"),
}
```

Remove any `LAUNCHER` entry, any `composable("launcher")` / `AppLauncherScreen(...)` call, and any nav action that targets a launcher route, from `AppNav.kt`. Leave Hero/Devices/Pair/Keyboard/Tuning wiring exactly as the base plan specifies.

- [ ] **A5 — Run, expect PASS**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.ui.AppNavTest`
Expected: PASS.

- [ ] **A6 — Commit**

```bash
git add app/src/main/kotlin/dev/atvremote/app/ui/AppNav.kt app/src/test/kotlin/dev/atvremote/app/ui/AppNavTest.kt
git commit -m "feat(app): remove app-launcher subsystem from Plan-3 nav (spec 2026-05-16)"
```

---

## Amendment Task B: Power → Hero (tap = Wake, long-press = Sleep)

**Files:**
- Modify: `app/src/main/kotlin/dev/atvremote/app/vm/RemoteViewModel.kt`
- Test: `app/src/test/kotlin/dev/atvremote/app/vm/RemoteViewModelTest.kt`

- [ ] **B1 — Write the failing test (add to base Task 10's `RemoteViewModelTest`)**

```kotlin
    @Test fun wakeCallsPowerTrue_sleepCallsPowerFalse() = runTest {
        val fake = FakeProtocol()                       // base plan's testutil fake CompanionSession
        val vm = RemoteViewModel(sessionProvider = { fake })
        vm.wake()
        vm.sleep()
        assertEquals(listOf(true, false), fake.powerCalls)
    }
```

(Base plan's `testutil/FakeProtocol.kt` records `CompanionSession` calls. Add `val powerCalls = mutableListOf<Boolean>()` and `override suspend fun power(on: Boolean) { powerCalls += on }` to it if base Task 10 did not already record `power`. `powerStatus()` is never called by the app — the FakeProtocol may keep its base default.)

- [ ] **B2 — Run, expect FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.vm.RemoteViewModelTest`
Expected: FAIL — `RemoteViewModel.wake`/`sleep` unresolved.

- [ ] **B3 — Implement `wake()`/`sleep()` on `RemoteViewModel`**

Add to `RemoteViewModel.kt` (using its existing coroutine scope + session accessor from base Task 10 — match its established pattern for issuing suspend session calls; do not introduce a new scope):

```kotlin
    /** Power: tap → Wake. No powerStatus readback (FetchAttentionState dead on tvOS 26.5). */
    fun wake() = launchSession { it.power(true) }

    /** Power: long-press → Sleep. */
    fun sleep() = launchSession { it.power(false) }
```

If base Task 10 named its helper differently than `launchSession`, use that exact helper (the one it uses for `button`/`media`); the requirement is: `wake()` issues `session.power(true)`, `sleep()` issues `session.power(false)`, each on the existing VM coroutine scope, each a no-op-safe call (the resilient session drops it while Reconnecting per Plan-2 §7 — no app-side handling needed).

- [ ] **B4 — Run, expect PASS**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.vm.RemoteViewModelTest`
Expected: PASS.

- [ ] **B5 — Commit**

```bash
git add app/src/main/kotlin/dev/atvremote/app/vm/RemoteViewModel.kt app/src/test/kotlin/dev/atvremote/app/vm/RemoteViewModelTest.kt app/src/test/kotlin/dev/atvremote/app/testutil/FakeProtocol.kt
git commit -m "feat(app): RemoteViewModel.wake/sleep (Power relocated to Hero)"
```

---

## Amendment Task C: Keyboard-capability gate

**Files:**
- Create: `app/src/main/kotlin/dev/atvremote/app/ui/hero/KeyboardCapability.kt`
- Test: `app/src/test/kotlin/dev/atvremote/app/ui/hero/KeyboardCapabilityTest.kt`

The Keyboard key must be present (physical-remote fidelity) but **disabled** until the deferred keyboard chain lands. `:protocol` keyboard members throw `kotlin.NotImplementedError` while stubbed; the app probes once at session-ready and exposes a boolean.

- [ ] **C1 — Write the failing test**

```kotlin
// app/src/test/kotlin/dev/atvremote/app/ui/hero/KeyboardCapabilityTest.kt
package dev.atvremote.app.ui.hero

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyboardCapabilityTest {
    @Test fun falseWhenTextGetThrowsNotImplemented() = runTest {
        val available = keyboardAvailable { throw NotImplementedError("stub") }
        assertFalse(available)
    }
    @Test fun trueWhenTextGetSucceeds() = runTest {
        val available = keyboardAvailable { "" }
        assertTrue(available)
    }
    @Test fun falseOnAnyOtherError() = runTest {
        val available = keyboardAvailable { error("boom") }
        assertFalse(available)
    }
}
```

- [ ] **C2 — Run, expect FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.ui.hero.KeyboardCapabilityTest`
Expected: FAIL — `keyboardAvailable` unresolved.

- [ ] **C3 — Implement the gate**

```kotlin
// app/src/main/kotlin/dev/atvremote/app/ui/hero/KeyboardCapability.kt
package dev.atvremote.app.ui.hero

/**
 * True iff the :protocol keyboard surface is implemented (deferred Plan-2
 * T15/T16/T19). While `textGet()` is a NotImplementedError stub the Keyboard
 * key renders disabled — a build-order state, not a permanent dead button
 * (spec 2026-05-16). Probe is a side-effect-free `textGet()` (read-only).
 */
suspend fun keyboardAvailable(textGetProbe: suspend () -> String): Boolean =
    try { textGetProbe(); true }
    catch (e: NotImplementedError) { false }
    catch (e: Throwable) { false }
```

- [ ] **C4 — Run, expect PASS**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.ui.hero.KeyboardCapabilityTest`
Expected: PASS (all three).

- [ ] **C5 — Commit**

```bash
git add app/src/main/kotlin/dev/atvremote/app/ui/hero/KeyboardCapability.kt app/src/test/kotlin/dev/atvremote/app/ui/hero/KeyboardCapabilityTest.kt
git commit -m "feat(app): keyboard-capability gate (Keyboard key disabled until T16)"
```

---

## Amendment Task D: Hero layout — physical-Siri-Remote faithful (Power top + bottom-left Keyboard key) + UI guards

**Files:**
- Modify: `app/src/main/kotlin/dev/atvremote/app/ui/hero/HeroScreen.kt` (base Task 14)
- Modify: `app/src/main/kotlin/dev/atvremote/app/ui/hero/ButtonRow.kt` (base Task 14)
- Test: `app/src/androidTest/kotlin/dev/atvremote/app/ui/HeroScreenUiTest.kt` (base plan)

Apply when executing base Task 14. The Hero layout, top→bottom, mirrors the physical remote: **Power (top-right)** → **Trackpad** (base Task 14 Composable, unchanged) → **row: Back · TV/Home** → **row: Play/Pause · Volume rocker** → **bottom-left: Keyboard key**. No Mute, no Siri (base Task 14 already excludes Siri).

- [ ] **D1 — Add failing Compose UI assertions to base `HeroScreenUiTest`**

```kotlin
    @Test fun heroHasPowerKeyboardNoLauncherNoMuteNoSiri() {
        composeRule.setContent { HeroScreen(vm = fakeRemoteVm(), onOpenKeyboard = {}) }
        composeRule.onNodeWithContentDescription("Power").assertExists()
        composeRule.onNodeWithContentDescription("Back").assertExists()
        composeRule.onNodeWithContentDescription("TV/Home").assertExists()
        composeRule.onNodeWithContentDescription("Play/Pause").assertExists()
        composeRule.onNodeWithContentDescription("Volume Up").assertExists()
        composeRule.onNodeWithContentDescription("Volume Down").assertExists()
        composeRule.onNodeWithContentDescription("Keyboard").assertExists()
        composeRule.onAllNodesWithContentDescription("Mute").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Siri").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Apps").assertCountEquals(0)
    }
```

(`fakeRemoteVm()` is the base plan's Hero UI-test helper providing a `RemoteViewModel` over `testutil/FakeProtocol`; `onOpenKeyboard` is the Hero→Keyboard nav callback wired in base Task 15.)

- [ ] **D2 — Run, expect FAIL**

Run: `./gradlew :app:connectedDebugAndroidTest --tests dev.atvremote.app.ui.HeroScreenUiTest` (or the base plan's Robolectric Compose harness if it runs these JVM-side)
Expected: FAIL — no Power/Keyboard nodes (base Hero lacks them).

- [ ] **D3 — Implement the Hero layout deltas**

In `HeroScreen.kt`, add to the top bar a Power control and at the bottom-left a Keyboard key. Use the base plan's existing Hero scaffold/Theme/Haptics; add only:

```kotlin
// Top bar, right-aligned (mirrors the physical remote's top ⏻):
// tap = Wake, long-press = Sleep. No state readback.
IconButton(
    onClick = { vm.wake(); haptics.tap() },
    modifier = Modifier
        .semantics { contentDescription = "Power" }
        .pointerInput(Unit) {
            detectTapGestures(onLongPress = { vm.sleep(); haptics.select() })
        },
) { Icon(Icons.Filled.PowerSettingsNew, contentDescription = null) }

// Bottom-left (physical remote's Mute slot) — Keyboard key.
// Disabled until the deferred keyboard chain lands (capability gate).
val kbReady by produceState(initialValue = false) {
    value = keyboardAvailable { vm.session().textGet() }
}
IconButton(
    onClick = { if (kbReady) onOpenKeyboard() },
    enabled = kbReady,
    modifier = Modifier.semantics { contentDescription = "Keyboard" },
) { Icon(Icons.Filled.Keyboard, contentDescription = null) }
```

`vm.session()` is the base Task 10 accessor RemoteViewModel exposes for the live `CompanionSession`; if base Task 10 named it differently, use that exact accessor. `onOpenKeyboard` is the Hero→Keyboard navigation lambda (base Task 15). In `ButtonRow.kt`, ensure each control carries the exact `contentDescription`s asserted in D1 (`Back`, `TV/Home`, `Play/Pause`, `Volume Up`, `Volume Down`) and that no Mute/Siri/Apps control exists. Keep the trackpad, button wiring, and volume rocker exactly as base Task 14 specifies.

- [ ] **D4 — Run, expect PASS**

Run: `./gradlew :app:connectedDebugAndroidTest --tests dev.atvremote.app.ui.HeroScreenUiTest`
Expected: PASS.

- [ ] **D5 — Full regression + commit**

Run: `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home ./gradlew :protocol:test :trace-tools:test` — expect the existing 120 green, 0 failures (this amendment does not touch `:protocol`/`:trace-tools`). Then `./gradlew :app:testDebugUnitTest` green.

```bash
git add app/src/main/kotlin/dev/atvremote/app/ui/hero/HeroScreen.kt app/src/main/kotlin/dev/atvremote/app/ui/hero/ButtonRow.kt app/src/androidTest/kotlin/dev/atvremote/app/ui/HeroScreenUiTest.kt
git commit -m "feat(app): Hero physical-Siri-Remote layout — Power top, Keyboard key (gated), no Mute/Siri/Apps"
```

---

## Self-review

- **Spec coverage:** physical-remote layout (Task D); app-launcher removed (Task A + File-Structure delta + D1 guard); Mute→Keyboard, no dead buttons (Task C gate + D); Power tap=Wake/long=Sleep, no powerStatus (Task B + D3); directional via HID (already in base Task 2 `TouchEvent.DirectionalStep` — noted, no task needed); §7 Reconnecting drop-no-replay (no app code — base resilient session handles it; B3 notes no app-side handling); keyboard-chain prerequisite + transient-disabled (Prerequisite section + Task C). All spec sections map to a task or an explicit "already satisfied" note.
- **Placeholder scan:** no TBD/TODO; every code step has complete code; "use base Task N's exact helper/accessor" is a precise instruction to read a defined symbol in the companion plan, not an under-specified placeholder (the base plan fully defines those symbols).
- **Type consistency:** `RemoteViewModel.wake()/sleep()` (B) match the D3 calls; `keyboardAvailable(suspend ()->String)` (C) matches the D3 `produceState` usage; `AppDestinations` route list (A4) matches A2's assertion; `contentDescription` strings in D1 match D3/ButtonRow.

---
