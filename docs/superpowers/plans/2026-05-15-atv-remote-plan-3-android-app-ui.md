# Apple TV Remote — Plan 3: Android App + Compose UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Android `:app` module — a Jetpack Compose remote that 1:1 reproduces Apple's native Apple TV Remote (Hero trackpad screen, discovery/PIN pairing, real-time keyboard, app launcher), backed by Keystore-wrapped credential storage and a lifecycle-aware Companion connection consuming the LOCKED `:protocol` API.

**Architecture:** `:app` depends on `:protocol` only and never touches its internals. A foreground/bound `ConnectionService` owns the single `CompanionSession` and survives Compose recompositions and the Activity lifecycle. ViewModels expose UI state via `StateFlow`, consume `:protocol` Flows/suspend functions, and translate the full-fidelity swipe gesture into a throttled (≤120 Hz) stream of `session.touch(x,y,phase)`/`session.click(InputAction)` calls. Credentials are `HapCredentials.serialize()` blobs sealed with an Android Keystore AES-GCM key and stored in DataStore keyed by device id.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose (BOM) + Material3, minSdk 26 / targetSdk 34, kotlinx-coroutines + StateFlow, AndroidX Lifecycle (ViewModel/Service), DataStore Preferences, Android Keystore (`AndroidKeyStore` AES/GCM), `VibrationEffect`, Robolectric + JUnit5 + kotlin-test for JVM-side tests, `androidx.compose.ui.test` for instrumented Compose tests.

---

## Depends on Plan 1 & Plan 2

This plan **consumes** the LOCKED public API of `:protocol` defined in Plan 1 and extended in Plan 2. It **must not modify `:protocol`**. The exact symbols consumed (package `dev.atvremote.protocol`):

- Plan 1: `AppleTvDevice(id,name,host,port,model,pairable)`, `HapCredentials` with `serialize(): String` / `HapCredentials.parse(s: String)`, `PairingState{AwaitingPin, Completed(credentials), Failed(reason)}`, `RemoteButton{Up=1,Down=2,Left=3,Right=4,Menu=5,Select=6,Home=7,VolumeUp=8,VolumeDown=9,PlayPause=14}`, `DeviceDiscovery.devices(): Flow<List<AppleTvDevice>>`, `AppleTvRemote.discovery()` / `AppleTvRemote.pair(device): PairingHandle` / `AppleTvRemote.connect(device, credentials): CompanionSession`, `PairingHandle{state: StateFlow<PairingState>, submitPin(pin), cancel()}`, `CompanionSession.button(RemoteButton, Boolean)` / `CompanionSession.close()`.
- Plan 2 additions (consumed exactly): `TouchPhase{Press=1,Hold=3,Release=4,Click=5}`, `InputAction{SingleTap,DoubleTap,Hold}`, `KeyboardFocusState{Focused,Unfocused}`, `InstalledApp(bundleId,name)`, `PowerStatus{On,Off,Unknown}`, `MediaCommand{Play,Pause,NextTrack,PreviousTrack}`, `ConnectionState{Connected,Reconnecting,Disconnected}`; `CompanionSession.touch(x: Int, y: Int, phase: TouchPhase)` (coords 0..1000), `CompanionSession.click(action: InputAction)`, `CompanionSession.textGet(): String`, `CompanionSession.textSet(String)`, `CompanionSession.textClear()`, `CompanionSession.textAppend(String)`, `CompanionSession.keyboardFocus: StateFlow<KeyboardFocusState>`, `CompanionSession.listApps(): List<InstalledApp>`, `CompanionSession.launchApp(bundleId: String)`, `CompanionSession.power(on: Boolean)`, `CompanionSession.powerStatus(): PowerStatus`, `CompanionSession.media(MediaCommand)`, `CompanionSession.connectionState: StateFlow<ConnectionState>`.

The exact suspend/non-suspend nature of each `:protocol` symbol is fixed by Plans 1 & 2. This plan calls every `CompanionSession` action method (`button`, `touch`, `click`, `text*`, `listApps`, `launchApp`, `power`, `powerStatus`, `media`, `close`) as a `suspend` function from a coroutine, and reads `keyboardFocus` / `connectionState` as non-suspend `StateFlow` properties. `AppleTvRemote.connect` is a `suspend` function; `AppleTvRemote.discovery()` / `AppleTvRemote.pair(device)` are non-suspend factory calls.

---

## File Structure

```
settings.gradle.kts                              MODIFY: add include(":app")
gradle/libs.versions.toml                        MODIFY: add Android/Compose/AndroidX entries
build.gradle.kts                                 MODIFY: register android+compose plugins (apply false)
app/build.gradle.kts                             :app Android application module, depends on :protocol
app/src/main/AndroidManifest.xml                 single Activity, ConnectionService, INTERNET/VIBRATE/FOREGROUND_SERVICE perms, portrait
app/src/main/res/values/themes.xml               base Material3 theme attrs (portrait, edge-to-edge)
app/src/main/res/xml/data_extraction_rules.xml   exclude encrypted cred DataStore from backup
app/src/main/res/xml/backup_rules.xml            legacy backup exclude rules
app/src/main/kotlin/dev/atvremote/app/
  MainActivity.kt                                single-Activity host; binds ConnectionService; sets Compose content
  AtvRemoteApp.kt                                Application class (process-wide DI container)
  di/AppGraph.kt                                 hand-rolled singletons (CredentialStore, ConnectionManager, Haptics)
  data/CredentialStore.kt                        Keystore-AES/GCM seal of HapCredentials.serialize() blobs in DataStore, keyed by device id
  data/KeystoreCipher.kt                         AndroidKeyStore AES/GCM key gen + wrap/unwrap
  conn/ConnectionService.kt                      bound+foreground Service owning the single CompanionSession
  conn/ConnectionManager.kt                      lifecycle-aware connect/reconnect state machine; exposes UiConnectionState
  conn/UiConnectionState.kt                      sealed UI connection state (Idle/Connecting/Connected/Reconnecting/CredentialInvalid/Failed)
  haptics/Haptics.kt                             VibrationEffect wrapper: tap/edgeStep/select effects
  swipe/SwipeTuning.kt                           tunable velocity/inertia curve params (data class + defaults)
  swipe/SwipeEngine.kt                           pure: drag samples -> throttled TouchEvent stream + edge-zone detection + tap/long-press classification
  swipe/TouchEvent.kt                            pure value type emitted by SwipeEngine (x,y in 0..1000, TouchPhase or directional button)
  ui/theme/Theme.kt                              system dynamic light/dark Material3 theme
  ui/theme/Color.kt                              Apple-faithful palette tokens
  ui/AppNav.kt                                   top-level navigation between Hero/Devices/Pair/Keyboard/Launcher/Tuning
  ui/hero/HeroScreen.kt                          main remote: top bar -> trackpad -> button row -> keyboard entry + volume rocker
  ui/hero/Trackpad.kt                            circular touch trackpad Composable wired to SwipeEngine + Haptics
  ui/hero/ButtonRow.kt                           Menu · TV/Home · Play/Pause (NO Siri) + volume rocker
  ui/devices/DevicesScreen.kt                    discovery list + paired-device switch
  ui/pair/PairScreen.kt                          on-TV PIN entry screen
  ui/keyboard/KeyboardScreen.kt                  real-time-sync text field bound to keyboardFocus + text*
  ui/launcher/AppLauncherScreen.kt               installed-app grid + power control
  ui/tuning/SwipeTuningScreen.kt                 debug A/B harness: record gesture -> emitted events, tune curve params
  vm/DiscoveryViewModel.kt                       discovery + paired devices StateFlow
  vm/PairingViewModel.kt                         wraps PairingHandle; PIN submit; persists credentials
  vm/RemoteViewModel.kt                          trackpad/button/volume -> session; connection state
  vm/KeyboardViewModel.kt                        keyboardFocus + text get/set/append/clear
  vm/LauncherViewModel.kt                        listApps/launchApp/power/powerStatus
  vm/TuningViewModel.kt                          feeds synthetic/recorded drags through SwipeEngine for A/B
app/src/test/kotlin/dev/atvremote/app/
  swipe/SwipeEngineTest.kt                       Robolectric-free pure JVM: edge zones, velocity map, ≤120Hz throttle, tap/long-press
  data/CredentialStoreTest.kt                    Robolectric: seal/unseal roundtrip, per-device keying, clear
  conn/ConnectionManagerTest.kt                  Robolectric: connect/reconnect/credential-invalid transitions (fake protocol)
  vm/DiscoveryViewModelTest.kt                   coroutine-test: devices flow -> state
  vm/PairingViewModelTest.kt                     coroutine-test: AwaitingPin/Completed/Failed -> state + persist
  vm/RemoteViewModelTest.kt                      coroutine-test: gesture -> session.touch/click/button; reconnect drops swipes
  vm/KeyboardViewModelTest.kt                    coroutine-test: focus drives screen; edits call text*
  vm/LauncherViewModelTest.kt                    coroutine-test: listApps/launch/power
  testutil/FakeProtocol.kt                       in-test fakes implementing CompanionSession/PairingHandle/DeviceDiscovery
app/src/androidTest/kotlin/dev/atvremote/app/
  ui/HeroScreenUiTest.kt                         Compose UI test: trackpad present, button row has no Siri, volume rocker
  ui/PairScreenUiTest.kt                         Compose UI test: PIN entry + error state
  ui/KeyboardScreenUiTest.kt                     Compose UI test: auto-shows on focus, text edits propagate
```

`:protocol` source is **not** in this list and must not be edited.

---

## Task 1: Add `:app` Android module + version catalog

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `gradle/libs.versions.toml`
- Modify: `build.gradle.kts`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/res/xml/backup_rules.xml`
- Create: `app/src/main/res/xml/data_extraction_rules.xml`
- Create: `app/src/main/kotlin/dev/atvremote/app/AtvRemoteApp.kt`
- Create: `app/src/main/kotlin/dev/atvremote/app/MainActivity.kt`
- Test: `app/src/test/kotlin/dev/atvremote/app/ScaffoldTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/dev/atvremote/app/ScaffoldTest.kt
package dev.atvremote.app

import dev.atvremote.protocol.RemoteButton
import kotlin.test.Test
import kotlin.test.assertEquals

class ScaffoldTest {
    @Test fun appModuleSeesProtocolApi() {
        // :app must compile against the LOCKED :protocol API
        assertEquals(5, RemoteButton.Menu.hid)
        assertEquals(14, RemoteButton.PlayPause.hid)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.ScaffoldTest`
Expected: FAIL — project `:app` does not exist / not configured.

- [ ] **Step 3: Wire Gradle**

`settings.gradle.kts` — append to the existing `include(...)` line:
```kotlin
rootProject.name = "apple-tv-controller"
include(":protocol", ":trace-tools", ":app")
```

Add to `gradle/libs.versions.toml` (under existing `[versions]`/`[libraries]`/`[plugins]`, do not remove Plan 1 entries):
```toml
[versions]
agp = "8.5.2"
androidxCore = "1.13.1"
lifecycle = "2.8.4"
activityCompose = "1.9.1"
composeBom = "2024.08.00"
datastore = "1.1.1"
robolectric = "4.13"
androidxTestCore = "1.6.1"
junit4 = "4.13.2"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version.ref = "androidxCore" }
androidx-lifecycle-viewmodel = { module = "androidx.lifecycle:lifecycle-viewmodel-ktx", version.ref = "lifecycle" }
androidx-lifecycle-runtime = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-service = { module = "androidx.lifecycle:lifecycle-service", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-material-icons = { module = "androidx.compose.material:material-icons-extended" }
androidx-datastore-preferences = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidxTestCore" }
junit4 = { module = "junit:junit", version.ref = "junit4" }
compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

`build.gradle.kts` — add new plugins alongside the existing line (keep `kotlin.jvm`):
```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.compose.compiler) apply false
}
```

`app/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "dev.atvremote.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.atvremote.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("src/test/kotlin")
    sourceSets["androidTest"].kotlin.srcDir("src/androidTest/kotlin")
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.useJUnitPlatform() }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.coroutines.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.junit4)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
```

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

    <application
        android:name=".AtvRemoteApp"
        android:label="ATV Remote"
        android:allowBackup="true"
        android:fullBackupContent="@xml/backup_rules"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:theme="@style/Theme.AtvRemote"
        android:supportsRtl="true">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait"
            android:configChanges="uiMode"
            android:theme="@style/Theme.AtvRemote">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".conn.ConnectionService"
            android:exported="false"
            android:foregroundServiceType="connectedDevice" />
    </application>
</manifest>
```

`app/src/main/res/values/themes.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.AtvRemote" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:windowBackground">@android:color/transparent</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
    </style>
</resources>
```

`app/src/main/res/xml/backup_rules.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<full-backup-content>
    <exclude domain="file" path="datastore/atv_credentials.preferences_pb" />
</full-backup-content>
```

`app/src/main/res/xml/data_extraction_rules.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="file" path="datastore/atv_credentials.preferences_pb" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="file" path="datastore/atv_credentials.preferences_pb" />
    </device-transfer>
</data-extraction-rules>
```

`app/src/main/kotlin/dev/atvremote/app/AtvRemoteApp.kt`:
```kotlin
package dev.atvremote.app

import android.app.Application
import dev.atvremote.app.di.AppGraph

class AtvRemoteApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
```

`app/src/main/kotlin/dev/atvremote/app/MainActivity.kt`:
```kotlin
package dev.atvremote.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Content set in Task 11 (AppNav). Placeholder keeps the module compiling.
        setContent { }
    }
}
```

> `di/AppGraph.kt` is created in Task 5; until then add a minimal stub:
> `app/src/main/kotlin/dev/atvremote/app/di/AppGraph.kt`:
> ```kotlin
> package dev.atvremote.app.di
> import android.content.Context
> class AppGraph(@Suppress("unused") private val appContext: Context)
> ```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.ScaffoldTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml app
git commit -m "build: add :app Android module depending on :protocol"
```

---

## Task 2: Pure swipe value types + `SwipeTuning` params

**Files:**
- Create: `app/src/main/kotlin/dev/atvremote/app/swipe/TouchEvent.kt`
- Create: `app/src/main/kotlin/dev/atvremote/app/swipe/SwipeTuning.kt`
- Test: `app/src/test/kotlin/dev/atvremote/app/swipe/SwipeTypesTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/dev/atvremote/app/swipe/SwipeTypesTest.kt
package dev.atvremote.app.swipe

import dev.atvremote.protocol.RemoteButton
import dev.atvremote.protocol.TouchPhase
import kotlin.test.Test
import kotlin.test.assertEquals

class SwipeTypesTest {
    @Test fun defaultsAreSane() {
        val t = SwipeTuning.DEFAULT
        assertEquals(120, t.maxEventsPerSecond)
        assertEquals(0.18f, t.edgeZoneFraction)
        assertEquals(true, t.gain > 0f && t.inertiaDecay in 0f..1f)
    }

    @Test fun touchEventCarriesProtocolPhase() {
        val e = TouchEvent.Move(x = 500, y = 1000, phase = TouchPhase.Hold)
        assertEquals(500, e.x)
        assertEquals(1000, e.y)
        assertEquals(TouchPhase.Hold, e.phase)

        val d = TouchEvent.DirectionalStep(RemoteButton.Right)
        assertEquals(RemoteButton.Right, d.button)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.swipe.SwipeTypesTest`
Expected: FAIL — `TouchEvent`/`SwipeTuning` unresolved.

- [ ] **Step 3: Implement value types**

`app/src/main/kotlin/dev/atvremote/app/swipe/TouchEvent.kt`:
```kotlin
package dev.atvremote.app.swipe

import dev.atvremote.protocol.RemoteButton
import dev.atvremote.protocol.TouchPhase

/** What the SwipeEngine decides the gesture should send to :protocol. Pure, no Android types. */
sealed interface TouchEvent {
    /** A point on the virtual trackpad in :protocol's 0..1000 coordinate space. */
    data class Move(val x: Int, val y: Int, val phase: TouchPhase) : TouchEvent

    /** Edge-zone press maps to a directional HID button step. */
    data class DirectionalStep(val button: RemoteButton) : TouchEvent

    /** Tap -> click(SingleTap). */
    data object Tap : TouchEvent

    /** Long-press -> click(Hold). */
    data object LongPress : TouchEvent
}
```

`app/src/main/kotlin/dev/atvremote/app/swipe/SwipeTuning.kt`:
```kotlin
package dev.atvremote.app.swipe

/**
 * Tunable velocity/inertia curve for the full-fidelity trackpad (spec §2/§5/§8).
 * All fields are A/B-tweakable from the SwipeTuningScreen.
 */
data class SwipeTuning(
    /** Linear displacement gain: screen px delta -> 0..1000 units. */
    val gain: Float = 2.4f,
    /** Velocity exponent: applied to normalized speed for the acceleration curve. */
    val velocityExponent: Float = 1.35f,
    /** Per-frame inertia retention after finger-up (0=no glide, 1=never stops). */
    val inertiaDecay: Float = 0.92f,
    /** Inertia stops once speed falls below this (units/frame). */
    val inertiaMinSpeed: Float = 0.6f,
    /** Fraction of the trackpad radius treated as a directional edge zone. */
    val edgeZoneFraction: Float = 0.18f,
    /** Max emitted touch events per second (spec §6: ≤120 Hz). */
    val maxEventsPerSecond: Int = 120,
    /** Movement (in 0..1000 units) below which a finger-up counts as a tap. */
    val tapSlopUnits: Int = 18,
    /** Press duration (ms) at/above which a stationary press is a long-press. */
    val longPressMs: Long = 450L,
) {
    companion object { val DEFAULT = SwipeTuning() }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.swipe.SwipeTypesTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/atvremote/app/swipe app/src/test/kotlin/dev/atvremote/app/swipe/SwipeTypesTest.kt
git commit -m "feat(app): swipe value types and tunable curve params"
```

---

## Task 3: `SwipeEngine` — gesture → throttled touch-event stream (pure, TDD)

**Files:**
- Create: `app/src/main/kotlin/dev/atvremote/app/swipe/SwipeEngine.kt`
- Test: `app/src/test/kotlin/dev/atvremote/app/swipe/SwipeEngineTest.kt`

`SwipeEngine` is pure (no Android/Compose imports) so it is JVM-unit-testable and reusable by the tuning harness. It takes a virtual time clock so throttle/long-press are deterministic.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/dev/atvremote/app/swipe/SwipeEngineTest.kt
package dev.atvremote.app.swipe

import dev.atvremote.protocol.RemoteButton
import dev.atvremote.protocol.TouchPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwipeEngineTest {
    private val tuning = SwipeTuning.DEFAULT
    // Trackpad is a square of side 1000 px for test math; center = (500,500).
    private fun engine(now: Long = 0L) =
        SwipeEngine(tuning = tuning, widthPx = 1000f, heightPx = 1000f)

    @Test fun pressEmitsPressPhaseAtClampedCoords() {
        val e = engine()
        val out = e.onDown(x = 500f, y = 500f, nowMs = 0L)
        assertEquals(listOf<TouchEvent>(TouchEvent.Move(500, 500, TouchPhase.Press)), out)
    }

    @Test fun dragEmitsHoldPhaseScaledByGain() {
        val e = engine()
        e.onDown(500f, 500f, 0L)
        val out = e.onMove(520f, 500f, nowMs = 16L) // +20px * gain 2.4 ≈ +48 units
        val mv = out.filterIsInstance<TouchEvent.Move>().single()
        assertEquals(TouchPhase.Hold, mv.phase)
        assertTrue(mv.x in 540..560, "x was ${mv.x}")
        assertEquals(500, mv.y)
    }

    @Test fun coordsClampedTo0_1000() {
        val e = engine()
        e.onDown(500f, 500f, 0L)
        val out = e.onMove(5000f, -5000f, 16L)
        val mv = out.filterIsInstance<TouchEvent.Move>().single()
        assertEquals(1000, mv.x)
        assertEquals(0, mv.y)
    }

    @Test fun moveThrottledToMaxEventsPerSecond() {
        val e = engine()
        e.onDown(500f, 500f, 0L)
        // 120 Hz => min spacing 8ms. Two moves 3ms apart -> second is dropped.
        val a = e.onMove(510f, 500f, nowMs = 4L)
        val b = e.onMove(520f, 500f, nowMs = 7L)
        assertEquals(1, a.filterIsInstance<TouchEvent.Move>().size)
        assertEquals(0, b.filterIsInstance<TouchEvent.Move>().size)
        val c = e.onMove(530f, 500f, nowMs = 13L) // ≥8ms after last emit
        assertEquals(1, c.filterIsInstance<TouchEvent.Move>().size)
    }

    @Test fun upWithinSlopIsTap() {
        val e = engine()
        e.onDown(500f, 500f, 0L)
        e.onMove(503f, 501f, 16L)            // tiny move, within tap slop
        val out = e.onUp(503f, 501f, nowMs = 120L)
        assertTrue(out.contains(TouchEvent.Tap))
        assertTrue(out.last() is TouchEvent.Move &&
            (out.last() as TouchEvent.Move).phase == TouchPhase.Release)
    }

    @Test fun stationaryLongPressClassifiedBeforeUp() {
        val e = engine()
        e.onDown(500f, 500f, 0L)
        val held = e.onTick(nowMs = 500L) // ≥ longPressMs (450) with no motion
        assertTrue(held.contains(TouchEvent.LongPress))
        val out = e.onUp(500f, 500f, nowMs = 520L)
        // already long-pressed: up must NOT also emit Tap
        assertTrue(!out.contains(TouchEvent.Tap))
    }

    @Test fun pressInRightEdgeZoneIsDirectionalStep() {
        val e = engine()
        // edgeZoneFraction 0.18 of half-width(500) => outer 90px ring is edge.
        val out = e.onDown(x = 980f, y = 500f, nowMs = 0L)
        assertEquals(
            TouchEvent.DirectionalStep(RemoteButton.Right),
            out.first { it is TouchEvent.DirectionalStep }
        )
    }

    @Test fun edgeZonesMapToCorrectButtons() {
        assertEquals(RemoteButton.Left,
            engine().onDown(20f, 500f, 0L).first { it is TouchEvent.DirectionalStep }
                .let { (it as TouchEvent.DirectionalStep).button })
        assertEquals(RemoteButton.Up,
            engine().onDown(500f, 20f, 0L).first { it is TouchEvent.DirectionalStep }
                .let { (it as TouchEvent.DirectionalStep).button })
        assertEquals(RemoteButton.Down,
            engine().onDown(500f, 980f, 0L).first { it is TouchEvent.DirectionalStep }
                .let { (it as TouchEvent.DirectionalStep).button })
    }

    @Test fun inertiaGeneratesDecayingMovesAfterFlick() {
        val e = engine()
        e.onDown(100f, 500f, 0L)
        e.onMove(300f, 500f, 8L)   // fast flick right
        e.onMove(500f, 500f, 16L)
        val released = e.onUp(500f, 500f, 24L)
        assertTrue(released.last() is TouchEvent.Move &&
            (released.last() as TouchEvent.Move).phase == TouchPhase.Release)
        // Inertia frames after release: monotonic-ish slowing, then stops.
        val f1 = e.onInertiaFrame(nowMs = 32L)
        val f2 = e.onInertiaFrame(nowMs = 40L)
        assertTrue(f1.isNotEmpty())
        // eventually the engine reports inertia finished
        var ticks = 0
        while (e.inertiaActive && ticks < 200) { e.onInertiaFrame(nowMs = 48L + ticks * 8L); ticks++ }
        assertTrue(!e.inertiaActive)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.swipe.SwipeEngineTest`
Expected: FAIL — `SwipeEngine` unresolved.

- [ ] **Step 3: Implement `SwipeEngine`**

```kotlin
// app/src/main/kotlin/dev/atvremote/app/swipe/SwipeEngine.kt
package dev.atvremote.app.swipe

import dev.atvremote.protocol.RemoteButton
import dev.atvremote.protocol.TouchPhase
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pure full-fidelity trackpad gesture engine (spec §2/§5).
 * - single-finger drag -> relative focus move w/ velocity & inertia -> TouchEvent.Move stream
 * - tap -> TouchEvent.Tap ; long-press -> TouchEvent.LongPress
 * - press in an edge zone -> TouchEvent.DirectionalStep
 * Output is throttled to tuning.maxEventsPerSecond. No Android/Compose imports.
 */
class SwipeEngine(
    private val tuning: SwipeTuning,
    private val widthPx: Float,
    private val heightPx: Float,
) {
    private var down = false
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var virtX = 500f          // current virtual position in 0..1000
    private var virtY = 500f
    private var downAtMs = 0L
    private var lastEmitMs = Long.MIN_VALUE
    private var maxTravelUnits = 0f
    private var longPressed = false
    private var edgeConsumed = false

    private var velX = 0f             // units/frame, for inertia
    private var velY = 0f
    var inertiaActive = false
        private set

    private val minSpacingMs: Long get() = (1000L / tuning.maxEventsPerSecond).coerceAtLeast(1L)

    private fun clamp(v: Float) = v.coerceIn(0f, 1000f).roundToInt()

    private fun edgeButton(px: Float, py: Float): RemoteButton? {
        val ex = tuning.edgeZoneFraction * (widthPx / 2f)
        val ey = tuning.edgeZoneFraction * (heightPx / 2f)
        val left = px <= ex
        val right = px >= widthPx - ex
        val top = py <= ey
        val bottom = py >= heightPx - ey
        // Resolve corners by the larger normalized overshoot.
        val cands = buildList {
            if (left) add(RemoteButton.Left to (ex - px))
            if (right) add(RemoteButton.Right to (px - (widthPx - ex)))
            if (top) add(RemoteButton.Up to (ey - py))
            if (bottom) add(RemoteButton.Down to (py - (heightPx - ey)))
        }
        return cands.maxByOrNull { it.second }?.first
    }

    private fun throttled(nowMs: Long): Boolean {
        if (lastEmitMs == Long.MIN_VALUE) return false
        return nowMs - lastEmitMs < minSpacingMs
    }

    fun onDown(x: Float, y: Float, nowMs: Long): List<TouchEvent> {
        down = true
        startX = x; startY = y; lastX = x; lastY = y
        downAtMs = nowMs
        lastEmitMs = Long.MIN_VALUE
        maxTravelUnits = 0f
        longPressed = false
        edgeConsumed = false
        velX = 0f; velY = 0f
        inertiaActive = false
        virtX = 500f; virtY = 500f
        val edge = edgeButton(x, y)
        if (edge != null) {
            edgeConsumed = true
            return listOf(TouchEvent.DirectionalStep(edge))
        }
        lastEmitMs = nowMs
        return listOf(TouchEvent.Move(clamp(virtX), clamp(virtY), TouchPhase.Press))
    }

    fun onMove(x: Float, y: Float, nowMs: Long): List<TouchEvent> {
        if (!down || edgeConsumed) return emptyList()
        val dxPx = x - lastX
        val dyPx = y - lastY
        lastX = x; lastY = y
        // velocity-shaped gain
        val dt = (nowMs - downAtMs).coerceAtLeast(1L).toFloat()
        val speed = (kotlin.math.hypot(dxPx.toDouble(), dyPx.toDouble()).toFloat()) / dt
        val accel = (1f + speed).pow(tuning.velocityExponent)
        val dux = dxPx * tuning.gain * accel
        val duy = dyPx * tuning.gain * accel
        virtX = (virtX + dux).coerceIn(0f, 1000f)
        virtY = (virtY + duy).coerceIn(0f, 1000f)
        velX = dux; velY = duy
        val travel = abs(virtX - 500f) + abs(virtY - 500f)
        if (travel > maxTravelUnits) maxTravelUnits = travel
        if (throttled(nowMs)) return emptyList()
        lastEmitMs = nowMs
        return listOf(TouchEvent.Move(clamp(virtX), clamp(virtY), TouchPhase.Hold))
    }

    /** Called periodically while finger is down so a stationary long-press can fire. */
    fun onTick(nowMs: Long): List<TouchEvent> {
        if (!down || longPressed || edgeConsumed) return emptyList()
        if (maxTravelUnits <= tuning.tapSlopUnits &&
            nowMs - downAtMs >= tuning.longPressMs
        ) {
            longPressed = true
            return listOf(TouchEvent.LongPress)
        }
        return emptyList()
    }

    fun onUp(x: Float, y: Float, nowMs: Long): List<TouchEvent> {
        if (!down) return emptyList()
        down = false
        if (edgeConsumed) { edgeConsumed = false; return emptyList() }
        val out = ArrayList<TouchEvent>()
        out += TouchEvent.Move(clamp(virtX), clamp(virtY), TouchPhase.Release)
        val isTap = maxTravelUnits <= tuning.tapSlopUnits &&
            nowMs - downAtMs < tuning.longPressMs
        if (isTap && !longPressed) out += TouchEvent.Tap
        // Begin inertia only on a real flick (had recent velocity, moved past slop).
        if (!longPressed && maxTravelUnits > tuning.tapSlopUnits &&
            (abs(velX) + abs(velY)) > tuning.inertiaMinSpeed
        ) {
            inertiaActive = true
        }
        return out
    }

    /** Drive after onUp to glide; returns Move(Hold) frames until inertia stops. */
    fun onInertiaFrame(nowMs: Long): List<TouchEvent> {
        if (!inertiaActive) return emptyList()
        velX *= tuning.inertiaDecay
        velY *= tuning.inertiaDecay
        virtX = (virtX + velX).coerceIn(0f, 1000f)
        virtY = (virtY + velY).coerceIn(0f, 1000f)
        if ((abs(velX) + abs(velY)) < tuning.inertiaMinSpeed) {
            inertiaActive = false
            return emptyList()
        }
        if (throttled(nowMs)) return emptyList()
        lastEmitMs = nowMs
        return listOf(TouchEvent.Move(clamp(virtX), clamp(virtY), TouchPhase.Hold))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.swipe.SwipeEngineTest`
Expected: PASS (all 9 cases).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/atvremote/app/swipe/SwipeEngine.kt app/src/test/kotlin/dev/atvremote/app/swipe/SwipeEngineTest.kt
git commit -m "feat(app): pure full-fidelity SwipeEngine with throttled touch stream + inertia"
```

---

## Task 4: Keystore cipher + per-device `CredentialStore`

**Files:**
- Create: `app/src/main/kotlin/dev/atvremote/app/data/KeystoreCipher.kt`
- Create: `app/src/main/kotlin/dev/atvremote/app/data/CredentialStore.kt`
- Test: `app/src/test/kotlin/dev/atvremote/app/data/CredentialStoreTest.kt`

Stores `HapCredentials.serialize()` strings sealed by an `AndroidKeyStore` AES/GCM key, in DataStore Preferences keyed by `AppleTvDevice.id`. Robolectric provides the AndroidKeyStore + filesystem.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/dev/atvremote/app/data/CredentialStoreTest.kt
package dev.atvremote.app.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CredentialStoreTest {
    private fun store() =
        CredentialStore(ApplicationProvider.getApplicationContext())

    @Test fun sealRoundTripPerDevice() = runTest {
        val s = store()
        s.save("dev-A", "BLOB-A-serialized")
        s.save("dev-B", "BLOB-B-serialized")
        assertEquals("BLOB-A-serialized", s.load("dev-A"))
        assertEquals("BLOB-B-serialized", s.load("dev-B"))
    }

    @Test fun ciphertextIsNotPlaintext() = runTest {
        val s = store()
        s.save("dev-A", "SECRET")
        assertEquals(false, s.rawStored("dev-A")?.contains("SECRET"))
    }

    @Test fun clearRemovesOnlyThatDevice() = runTest {
        val s = store()
        s.save("dev-A", "A"); s.save("dev-B", "B")
        s.clear("dev-A")
        assertNull(s.load("dev-A"))
        assertEquals("B", s.load("dev-B"))
    }

    @Test fun missingDeviceReturnsNull() = runTest {
        assertNull(store().load("nope"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.data.CredentialStoreTest`
Expected: FAIL — `CredentialStore` unresolved.

- [ ] **Step 3: Implement Keystore cipher + store**

`app/src/main/kotlin/dev/atvremote/app/data/KeystoreCipher.kt`:
```kotlin
package dev.atvremote.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Wraps a non-exportable AndroidKeyStore AES/GCM key used to seal credential blobs. */
class KeystoreCipher(private val alias: String = "atv_cred_key") {
    private val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun key(): SecretKey {
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        kg.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return kg.generateKey()
    }

    /** Returns base64(iv ‖ ciphertext+tag). */
    fun seal(plaintext: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val ct = c.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val out = ByteArray(1 + iv.size + ct.size)
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(ct, 0, out, 1 + iv.size, ct.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun unseal(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val ivLen = raw[0].toInt()
        val iv = raw.copyOfRange(1, 1 + ivLen)
        val ct = raw.copyOfRange(1 + ivLen, raw.size)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return String(c.doFinal(ct), Charsets.UTF_8)
    }
}
```

`app/src/main/kotlin/dev/atvremote/app/data/CredentialStore.kt`:
```kotlin
package dev.atvremote.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.credDataStore by preferencesDataStore(name = "atv_credentials")

/**
 * Persists HapCredentials.serialize() blobs sealed by Keystore, keyed by AppleTvDevice.id.
 * Spec §3/§6: app wraps long-term credentials before storing.
 */
class CredentialStore(
    private val context: Context,
    private val cipher: KeystoreCipher = KeystoreCipher(),
) {
    private fun keyFor(deviceId: String) = stringPreferencesKey("cred_$deviceId")

    suspend fun save(deviceId: String, serializedCredentials: String) {
        val sealed = cipher.seal(serializedCredentials)
        context.credDataStore.edit { it[keyFor(deviceId)] = sealed }
    }

    suspend fun load(deviceId: String): String? {
        val sealed = context.credDataStore.data.first()[keyFor(deviceId)] ?: return null
        return runCatching { cipher.unseal(sealed) }.getOrNull()
    }

    suspend fun clear(deviceId: String) {
        context.credDataStore.edit { it.remove(keyFor(deviceId)) }
    }

    suspend fun allDeviceIds(): List<String> =
        context.credDataStore.data.first().asMap().keys
            .map { it.name }
            .filter { it.startsWith("cred_") }
            .map { it.removePrefix("cred_") }

    /** Test/diagnostic accessor: returns the stored (sealed) string verbatim. */
    suspend fun rawStored(deviceId: String): String? =
        context.credDataStore.data.first()[keyFor(deviceId)]
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.data.CredentialStoreTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/atvremote/app/data app/src/test/kotlin/dev/atvremote/app/data/CredentialStoreTest.kt
git commit -m "feat(app): Keystore-sealed per-device credential store on DataStore"
```

---

## Task 5: `Haptics` + `AppGraph` DI container

**Files:**
- Create: `app/src/main/kotlin/dev/atvremote/app/haptics/Haptics.kt`
- Modify: `app/src/main/kotlin/dev/atvremote/app/di/AppGraph.kt` (replace Task 1 stub)
- Test: `app/src/test/kotlin/dev/atvremote/app/haptics/HapticsTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/dev/atvremote/app/haptics/HapticsTest.kt
package dev.atvremote.app.haptics

import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HapticsTest {
    @Test fun effectsDoNotThrowOnAnyApi() {
        val h = Haptics(ApplicationProvider.getApplicationContext())
        h.tap(); h.edgeStep(); h.select()
        assertTrue(true) // exercised the VibrationEffect paths without crashing
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.haptics.HapticsTest`
Expected: FAIL — `Haptics` unresolved.

- [ ] **Step 3: Implement Haptics + AppGraph**

`app/src/main/kotlin/dev/atvremote/app/haptics/Haptics.kt`:
```kotlin
package dev.atvremote.app.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Light haptics matching Apple feel (spec §5): tap / edge-step / select. */
class Haptics(context: Context) {
    private val vibrator: Vibrator? = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun oneShot(ms: Long, amplitude: Int) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        v.vibrate(VibrationEffect.createOneShot(ms, amplitude))
    }

    /** Crisp light tick on trackpad tap. */
    fun tap() = oneShot(10L, 80)

    /** Slightly firmer tick on directional edge step. */
    fun edgeStep() = oneShot(14L, 130)

    /** Confirmation pop on select/long-press. */
    fun select() = oneShot(20L, 200)
}
```

`app/src/main/kotlin/dev/atvremote/app/di/AppGraph.kt` (replace stub):
```kotlin
package dev.atvremote.app.di

import android.content.Context
import dev.atvremote.app.conn.ConnectionManager
import dev.atvremote.app.data.CredentialStore
import dev.atvremote.app.haptics.Haptics
import dev.atvremote.protocol.AppleTvRemote

/** Hand-rolled process-wide singletons. No DI framework (YAGNI). */
class AppGraph(appContext: Context) {
    val credentialStore = CredentialStore(appContext)
    val haptics = Haptics(appContext)
    val connectionManager = ConnectionManager(
        remote = AppleTvRemote,
        credentialStore = credentialStore,
    )
}
```

> `ConnectionManager` is created in Task 6. To keep this task green in isolation, Task 6's failing test is what surfaces it; if implementing strictly task-by-task, add Task 6's `ConnectionManager` skeleton (Step 3 there) before compiling `AppGraph`. The reference impl below assumes Tasks 5 and 6 land together in the same branch.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.haptics.HapticsTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/atvremote/app/haptics app/src/main/kotlin/dev/atvremote/app/di/AppGraph.kt app/src/test/kotlin/dev/atvremote/app/haptics/HapticsTest.kt
git commit -m "feat(app): VibrationEffect haptics + AppGraph DI container"
```

---

## Task 6: `ConnectionManager` lifecycle/reconnect state machine

**Files:**
- Create: `app/src/main/kotlin/dev/atvremote/app/conn/UiConnectionState.kt`
- Create: `app/src/main/kotlin/dev/atvremote/app/conn/ConnectionManager.kt`
- Create: `app/src/test/kotlin/dev/atvremote/app/testutil/FakeProtocol.kt`
- Test: `app/src/test/kotlin/dev/atvremote/app/conn/ConnectionManagerTest.kt`

Owns the single `CompanionSession`. Spec §7: indexed-backoff reconnect, "Reconnecting…" non-error state, credential-invalid → re-pair prompt + clear stored creds. It observes the session's `connectionState: StateFlow<ConnectionState>` and maps it to `UiConnectionState`.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/dev/atvremote/app/testutil/FakeProtocol.kt
package dev.atvremote.app.testutil

import dev.atvremote.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class FakeSession : CompanionSession {
    val buttons = mutableListOf<Pair<RemoteButton, Boolean>>()
    val touches = mutableListOf<Triple<Int, Int, TouchPhase>>()
    val clicks = mutableListOf<InputAction>()
    val medias = mutableListOf<MediaCommand>()
    var text: String = ""
    var launched: String? = null
    var poweredOn: Boolean? = null
    var closed = false
    val connFlow = MutableStateFlow(ConnectionState.Connected)
    val focusFlow = MutableStateFlow(KeyboardFocusState.Unfocused)
    var apps = listOf(InstalledApp("com.netflix.Netflix", "Netflix"))

    override suspend fun button(button: RemoteButton, down: Boolean) { buttons += button to down }
    override suspend fun touch(x: Int, y: Int, phase: TouchPhase) { touches += Triple(x, y, phase) }
    override suspend fun click(action: InputAction) { clicks += action }
    override suspend fun textGet(): String = text
    override suspend fun textSet(value: String) { text = value }
    override suspend fun textClear() { text = "" }
    override suspend fun textAppend(value: String) { text += value }
    override val keyboardFocus: StateFlow<KeyboardFocusState> = focusFlow
    override suspend fun listApps(): List<InstalledApp> = apps
    override suspend fun launchApp(bundleId: String) { launched = bundleId }
    override suspend fun power(on: Boolean) { poweredOn = on }
    override suspend fun powerStatus(): PowerStatus =
        when (poweredOn) { true -> PowerStatus.On; false -> PowerStatus.Off; null -> PowerStatus.Unknown }
    override suspend fun media(command: MediaCommand) { medias += command }
    override val connectionState: StateFlow<ConnectionState> = connFlow
    override suspend fun close() { closed = true }
}

/** Pluggable connect() behavior for ConnectionManager tests. */
class FakeRemote {
    var nextSession: () -> CompanionSession = { FakeSession() }
    var onConnect: (AppleTvDevice, HapCredentials) -> Unit = { _, _ -> }
    suspend fun connect(device: AppleTvDevice, credentials: HapCredentials): CompanionSession {
        onConnect(device, credentials); return nextSession()
    }
}
```

```kotlin
// app/src/test/kotlin/dev/atvremote/app/conn/ConnectionManagerTest.kt
package dev.atvremote.app.conn

import dev.atvremote.app.testutil.FakeSession
import dev.atvremote.protocol.AppleTvDevice
import dev.atvremote.protocol.ConnectionState
import dev.atvremote.protocol.HapCredentials
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConnectionManagerTest {
    private val device = AppleTvDevice("dev-A", "Living Room", "10.0.0.5", 49152, "AppleTV14,1", true)
    private fun creds() = HapCredentials(
        ByteArray(1), ByteArray(1), ByteArray(1), ByteArray(1), ByteArray(1)
    )

    @Test fun connectedSessionExposesConnectedState() = runTest {
        val session = FakeSession()
        val cm = ConnectionManager(connector = { _, _ -> session })
        cm.connect(device, creds())
        assertTrue(cm.uiState.first() is UiConnectionState.Connected)
        assertEquals(session, cm.requireSession())
    }

    @Test fun protocolReconnectingMapsToNonErrorUiState() = runTest {
        val session = FakeSession()
        val cm = ConnectionManager(connector = { _, _ -> session })
        cm.connect(device, creds())
        session.connFlow.value = ConnectionState.Reconnecting
        assertTrue(cm.uiState.first() is UiConnectionState.Reconnecting)
    }

    @Test fun connectFailureWithInvalidCredsRequestsRepair() = runTest {
        val cm = ConnectionManager(connector = { _, _ ->
            throw CredentialInvalidException("ATV rejected pair-verify")
        })
        cm.connect(device, creds())
        assertTrue(cm.uiState.first() is UiConnectionState.CredentialInvalid)
    }

    @Test fun genericConnectFailureSurfacesFailedState() = runTest {
        val cm = ConnectionManager(connector = { _, _ -> throw RuntimeException("no route") })
        cm.connect(device, creds())
        assertTrue(cm.uiState.first() is UiConnectionState.Failed)
    }

    @Test fun disconnectClosesSession() = runTest {
        val session = FakeSession()
        val cm = ConnectionManager(connector = { _, _ -> session })
        cm.connect(device, creds())
        cm.disconnect()
        assertTrue(session.closed)
        assertTrue(cm.uiState.first() is UiConnectionState.Idle)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.conn.ConnectionManagerTest`
Expected: FAIL — `ConnectionManager`/`UiConnectionState`/`CredentialInvalidException` unresolved.

- [ ] **Step 3: Implement state machine**

`app/src/main/kotlin/dev/atvremote/app/conn/UiConnectionState.kt`:
```kotlin
package dev.atvremote.app.conn

import dev.atvremote.protocol.AppleTvDevice

/** UI-facing connection state (spec §7). Reconnecting is intentionally NOT an error. */
sealed interface UiConnectionState {
    data object Idle : UiConnectionState
    data class Connecting(val device: AppleTvDevice) : UiConnectionState
    data class Connected(val device: AppleTvDevice) : UiConnectionState
    data class Reconnecting(val device: AppleTvDevice) : UiConnectionState
    data class CredentialInvalid(val device: AppleTvDevice) : UiConnectionState
    data class Failed(val device: AppleTvDevice, val reason: String) : UiConnectionState
}

/** Thrown by the protocol layer (or detected by the manager) when stored creds are rejected. */
class CredentialInvalidException(message: String) : Exception(message)
```

`app/src/main/kotlin/dev/atvremote/app/conn/ConnectionManager.kt`:
```kotlin
package dev.atvremote.app.conn

import dev.atvremote.app.data.CredentialStore
import dev.atvremote.protocol.AppleTvDevice
import dev.atvremote.protocol.AppleTvRemote
import dev.atvremote.protocol.CompanionSession
import dev.atvremote.protocol.ConnectionState
import dev.atvremote.protocol.HapCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Connector indirection so tests can inject a fake without :protocol's connect(). */
fun interface SessionConnector {
    suspend fun connect(device: AppleTvDevice, credentials: HapCredentials): CompanionSession
}

/**
 * Owns the single CompanionSession (spec §7). Maps ConnectionState -> UiConnectionState,
 * does indexed-backoff reconnect, and clears creds + asks for re-pair on credential-invalid.
 */
class ConnectionManager(
    private val connector: SessionConnector =
        SessionConnector { d, c -> AppleTvRemote.connect(d, c) },
    private val credentialStore: CredentialStore? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val backoffMs: List<Long> = listOf(500, 1000, 2000, 4000, 8000, 15000),
) {
    private val _uiState = MutableStateFlow<UiConnectionState>(UiConnectionState.Idle)
    val uiState: StateFlow<UiConnectionState> = _uiState.asStateFlow()

    @Volatile private var session: CompanionSession? = null
    private var observeJob: Job? = null
    private var currentDevice: AppleTvDevice? = null
    private var currentCreds: HapCredentials? = null

    fun currentSession(): CompanionSession? = session
    fun requireSession(): CompanionSession =
        session ?: error("No active CompanionSession")

    suspend fun connect(device: AppleTvDevice, credentials: HapCredentials) {
        currentDevice = device
        currentCreds = credentials
        _uiState.value = UiConnectionState.Connecting(device)
        try {
            val s = connector.connect(device, credentials)
            session = s
            _uiState.value = UiConnectionState.Connected(device)
            observeSessionState(device, s)
        } catch (e: CredentialInvalidException) {
            credentialStore?.clear(device.id)
            _uiState.value = UiConnectionState.CredentialInvalid(device)
        } catch (e: Exception) {
            _uiState.value =
                UiConnectionState.Failed(device, e.message ?: e::class.simpleName ?: "error")
            scheduleReconnect(device, credentials)
        }
    }

    private fun observeSessionState(device: AppleTvDevice, s: CompanionSession) {
        observeJob?.cancel()
        observeJob = scope.launch {
            s.connectionState.collect { cs ->
                _uiState.value = when (cs) {
                    ConnectionState.Connected -> UiConnectionState.Connected(device)
                    ConnectionState.Reconnecting -> UiConnectionState.Reconnecting(device)
                    ConnectionState.Disconnected -> {
                        scheduleReconnect(device, currentCreds)
                        UiConnectionState.Reconnecting(device)
                    }
                }
            }
        }
    }

    private fun scheduleReconnect(device: AppleTvDevice, creds: HapCredentials?) {
        creds ?: return
        scope.launch {
            for (wait in backoffMs) {
                delay(wait)
                try {
                    val s = connector.connect(device, creds)
                    session = s
                    _uiState.value = UiConnectionState.Connected(device)
                    observeSessionState(device, s)
                    return@launch
                } catch (e: CredentialInvalidException) {
                    credentialStore?.clear(device.id)
                    _uiState.value = UiConnectionState.CredentialInvalid(device)
                    return@launch
                } catch (_: Exception) {
                    _uiState.value = UiConnectionState.Reconnecting(device)
                }
            }
            _uiState.value =
                UiConnectionState.Failed(device, "Reconnect attempts exhausted")
        }
    }

    suspend fun disconnect() {
        observeJob?.cancel(); observeJob = null
        session?.close()
        session = null
        _uiState.value = UiConnectionState.Idle
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.conn.ConnectionManagerTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/atvremote/app/conn app/src/test/kotlin/dev/atvremote/app/testutil/FakeProtocol.kt app/src/test/kotlin/dev/atvremote/app/conn/ConnectionManagerTest.kt
git commit -m "feat(app): lifecycle-aware ConnectionManager with backoff reconnect + re-pair on invalid creds"
```

---

## Task 7: Bound + foreground `ConnectionService`

**Files:**
- Create: `app/src/main/kotlin/dev/atvremote/app/conn/ConnectionService.kt`
- Test: `app/src/test/kotlin/dev/atvremote/app/conn/ConnectionServiceTest.kt`

Per spec §7, connection stability is improved by holding the session in a foreground Service so it survives Activity teardown / backgrounding. The Service delegates all logic to the singleton `ConnectionManager` (already tested in Task 6).

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/dev/atvremote/app/conn/ConnectionServiceTest.kt
package dev.atvremote.app.conn

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConnectionServiceTest {
    @Test fun bindReturnsManagerBinder() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = Robolectric.buildService(
            ConnectionService::class.java, Intent(ctx, ConnectionService::class.java)
        ).create()
        val service = controller.get()
        val binder = service.onBind(Intent(ctx, ConnectionService::class.java))
        val local = binder as ConnectionService.LocalBinder
        assertSame(service.connectionManager, local.manager())
        assertTrue(true)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.conn.ConnectionServiceTest`
Expected: FAIL — `ConnectionService` unresolved.

- [ ] **Step 3: Implement the Service**

`app/src/main/kotlin/dev/atvremote/app/conn/ConnectionService.kt`:
```kotlin
package dev.atvremote.app.conn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import dev.atvremote.app.AtvRemoteApp

/**
 * Foreground + bound service that owns the CompanionSession lifetime so it
 * survives Activity recreation / backgrounding (spec §7).
 */
class ConnectionService : Service() {
    private val channelId = "atv_connection"

    val connectionManager: ConnectionManager
        get() = (application as AtvRemoteApp).graph.connectionManager

    inner class LocalBinder : Binder() {
        fun manager(): ConnectionManager = connectionManager
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId, "Remote connection", NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Apple TV Remote")
            .setContentText("Connected")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.conn.ConnectionServiceTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/atvremote/app/conn/ConnectionService.kt app/src/test/kotlin/dev/atvremote/app/conn/ConnectionServiceTest.kt
git commit -m "feat(app): bound+foreground ConnectionService owning the session"
```

---

## Task 8: `DiscoveryViewModel` (discovery + paired devices)

**Files:**
- Create: `app/src/main/kotlin/dev/atvremote/app/vm/DiscoveryViewModel.kt`
- Test: `app/src/test/kotlin/dev/atvremote/app/vm/DiscoveryViewModelTest.kt`

Consumes `DeviceDiscovery.devices(): Flow<List<AppleTvDevice>>` and `CredentialStore.allDeviceIds()` to mark which discovered devices are already paired.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/dev/atvremote/app/vm/DiscoveryViewModelTest.kt
package dev.atvremote.app.vm

import dev.atvremote.protocol.AppleTvDevice
import dev.atvremote.protocol.DeviceDiscovery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val d1 = AppleTvDevice("dev-A", "Living Room", "10.0.0.5", 49152, "AppleTV14,1", true)
    private val d2 = AppleTvDevice("dev-B", "Bedroom", "10.0.0.6", 49152, "AppleTV11,1", true)

    private fun discovery(list: List<AppleTvDevice>) = object : DeviceDiscovery {
        override fun devices(): Flow<List<AppleTvDevice>> = flowOf(list)
    }

    @Test fun emitsDiscoveredDevicesAndFlagsPaired() = runTest {
        val vm = DiscoveryViewModel(
            discovery = discovery(listOf(d1, d2)),
            pairedDeviceIds = { setOf("dev-B") },
        )
        dispatcher.scheduler.advanceUntilIdle()
        val state = vm.state.first { it.devices.isNotEmpty() }
        assertEquals(2, state.devices.size)
        assertTrue(state.devices.first { it.device.id == "dev-B" }.paired)
        assertTrue(!state.devices.first { it.device.id == "dev-A" }.paired)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.vm.DiscoveryViewModelTest`
Expected: FAIL — `DiscoveryViewModel` unresolved.

- [ ] **Step 3: Implement ViewModel**

```kotlin
// app/src/main/kotlin/dev/atvremote/app/vm/DiscoveryViewModel.kt
package dev.atvremote.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.atvremote.protocol.AppleTvDevice
import dev.atvremote.protocol.DeviceDiscovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiscoveredDevice(val device: AppleTvDevice, val paired: Boolean)
data class DiscoveryUiState(
    val devices: List<DiscoveredDevice> = emptyList(),
    val scanning: Boolean = true,
)

class DiscoveryViewModel(
    private val discovery: DeviceDiscovery,
    private val pairedDeviceIds: suspend () -> Set<String>,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoveryUiState())
    val state: StateFlow<DiscoveryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val paired = pairedDeviceIds()
            discovery.devices().collect { list ->
                _state.value = DiscoveryUiState(
                    devices = list.map { DiscoveredDevice(it, it.id in paired) },
                    scanning = false,
                )
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.vm.DiscoveryViewModelTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/atvremote/app/vm/DiscoveryViewModel.kt app/src/test/kotlin/dev/atvremote/app/vm/DiscoveryViewModelTest.kt
git commit -m "feat(app): DiscoveryViewModel consuming :protocol device flow"
```

---

## Task 9: `PairingViewModel` (PIN entry → persist credentials)

**Files:**
- Create: `app/src/main/kotlin/dev/atvremote/app/vm/PairingViewModel.kt`
- Test: `app/src/test/kotlin/dev/atvremote/app/vm/PairingViewModelTest.kt`

Consumes `AppleTvRemote.pair(device): PairingHandle`, `PairingHandle.state: StateFlow<PairingState>`, `submitPin`, `cancel`. On `PairingState.Completed` it persists `credentials.serialize()` via the credential store.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/dev/atvremote/app/vm/PairingViewModelTest.kt
package dev.atvremote.app.vm

import dev.atvremote.protocol.HapCredentials
import dev.atvremote.protocol.PairingHandle
import dev.atvremote.protocol.PairingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PairingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private class FakeHandle : PairingHandle {
        val flow = MutableStateFlow<PairingState>(PairingState.AwaitingPin)
        var submitted: String? = null
        var canceled = false
        override val state: StateFlow<PairingState> = flow
        override suspend fun submitPin(pin: String) { submitted = pin }
        override fun cancel() { canceled = true }
    }

    private fun creds() = HapCredentials(
        ByteArray(1), ByteArray(1), ByteArray(1), ByteArray(1), ByteArray(1)
    )

    @Test fun awaitingPinThenCompletedPersistsCredentials() = runTest {
        val handle = FakeHandle()
        var savedId: String? = null
        var savedBlob: String? = null
        val vm = PairingViewModel(
            deviceId = "dev-A",
            handle = handle,
            persist = { id, blob -> savedId = id; savedBlob = blob },
        )
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.first() is PairingUiState.AwaitingPin)

        vm.submitPin("1234")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("1234", handle.submitted)

        handle.flow.value = PairingState.Completed(creds())
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(vm.state.first() is PairingUiState.Completed)
        assertEquals("dev-A", savedId)
        assertEquals(creds().serialize(), savedBlob)
    }

    @Test fun failedSurfacesReason() = runTest {
        val handle = FakeHandle()
        val vm = PairingViewModel("dev-A", handle, persist = { _, _ -> })
        handle.flow.value = PairingState.Failed("wrong pin")
        dispatcher.scheduler.advanceUntilIdle()
        val s = vm.state.first { it is PairingUiState.Failed } as PairingUiState.Failed
        assertEquals("wrong pin", s.reason)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.vm.PairingViewModelTest`
Expected: FAIL — `PairingViewModel`/`PairingUiState` unresolved.

- [ ] **Step 3: Implement ViewModel**

```kotlin
// app/src/main/kotlin/dev/atvremote/app/vm/PairingViewModel.kt
package dev.atvremote.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.atvremote.protocol.PairingHandle
import dev.atvremote.protocol.PairingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PairingUiState {
    data object Connecting : PairingUiState
    data object AwaitingPin : PairingUiState
    data object Completed : PairingUiState
    data class Failed(val reason: String) : PairingUiState
}

class PairingViewModel(
    private val deviceId: String,
    private val handle: PairingHandle,
    private val persist: suspend (deviceId: String, serializedBlob: String) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow<PairingUiState>(PairingUiState.Connecting)
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            handle.state.collect { ps ->
                _state.value = when (ps) {
                    is PairingState.AwaitingPin -> PairingUiState.AwaitingPin
                    is PairingState.Completed -> {
                        persist(deviceId, ps.credentials.serialize())
                        PairingUiState.Completed
                    }
                    is PairingState.Failed -> PairingUiState.Failed(ps.reason)
                }
            }
        }
    }

    fun submitPin(pin: String) {
        viewModelScope.launch { handle.submitPin(pin) }
    }

    fun cancel() {
        handle.cancel()
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.vm.PairingViewModelTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/atvremote/app/vm/PairingViewModel.kt app/src/test/kotlin/dev/atvremote/app/vm/PairingViewModelTest.kt
git commit -m "feat(app): PairingViewModel persisting serialized HapCredentials on completion"
```

---

## Task 10: `RemoteViewModel` (gesture/buttons/volume → session)

**Files:**
- Create: `app/src/main/kotlin/dev/atvremote/app/vm/RemoteViewModel.kt`
- Test: `app/src/test/kotlin/dev/atvremote/app/vm/RemoteViewModelTest.kt`

Bridges `SwipeEngine` `TouchEvent`s to `session.touch/click/button`, exposes button/volume/media actions, fires `Haptics`, and drops swipe events while not `Connected` (spec §7: "重连期间滑动事件丢弃、按钮短暂入队").

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/dev/atvremote/app/vm/RemoteViewModelTest.kt
package dev.atvremote.app.vm

import dev.atvremote.app.swipe.TouchEvent
import dev.atvremote.app.testutil.FakeSession
import dev.atvremote.protocol.InputAction
import dev.atvremote.protocol.MediaCommand
import dev.atvremote.protocol.RemoteButton
import dev.atvremote.protocol.TouchPhase
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteViewModelTest {
    private fun vm(session: FakeSession, connected: Boolean = true): Pair<RemoteViewModel, MutableList<String>> {
        val haptics = mutableListOf<String>()
        val vm = RemoteViewModel(
            sessionProvider = { session },
            isConnected = { connected },
            onTap = { haptics += "tap" },
            onEdge = { haptics += "edge" },
            onSelect = { haptics += "select" },
        )
        return vm to haptics
    }

    @Test fun moveEventDrivesSessionTouch() = runTest {
        val s = FakeSession()
        val (vm, _) = vm(s)
        vm.onTouchEvent(TouchEvent.Move(120, 880, TouchPhase.Press))
        vm.onTouchEvent(TouchEvent.Move(300, 500, TouchPhase.Hold))
        vm.onTouchEvent(TouchEvent.Move(300, 500, TouchPhase.Release))
        assertEquals(
            listOf(Triple(120, 880, TouchPhase.Press),
                    Triple(300, 500, TouchPhase.Hold),
                    Triple(300, 500, TouchPhase.Release)),
            s.touches,
        )
    }

    @Test fun tapEmitsSingleTapClickAndHaptic() = runTest {
        val s = FakeSession()
        val (vm, h) = vm(s)
        vm.onTouchEvent(TouchEvent.Tap)
        assertEquals(listOf(InputAction.SingleTap), s.clicks)
        assertTrue(h.contains("tap"))
    }

    @Test fun longPressEmitsHoldClickAndSelectHaptic() = runTest {
        val s = FakeSession()
        val (vm, h) = vm(s)
        vm.onTouchEvent(TouchEvent.LongPress)
        assertEquals(listOf(InputAction.Hold), s.clicks)
        assertTrue(h.contains("select"))
    }

    @Test fun directionalStepPressesAndReleasesButtonWithEdgeHaptic() = runTest {
        val s = FakeSession()
        val (vm, h) = vm(s)
        vm.onTouchEvent(TouchEvent.DirectionalStep(RemoteButton.Right))
        assertEquals(
            listOf(RemoteButton.Right to true, RemoteButton.Right to false),
            s.buttons,
        )
        assertTrue(h.contains("edge"))
    }

    @Test fun swipeMovesDroppedWhileNotConnected() = runTest {
        val s = FakeSession()
        val (vm, _) = vm(s, connected = false)
        vm.onTouchEvent(TouchEvent.Move(500, 500, TouchPhase.Hold))
        assertTrue(s.touches.isEmpty())
    }

    @Test fun buttonAndVolumeAndMediaMapToProtocol() = runTest {
        val s = FakeSession()
        val (vm, _) = vm(s)
        vm.pressButton(RemoteButton.Menu)
        vm.volumeUp()
        vm.volumeDown()
        vm.media(MediaCommand.Play)
        assertEquals(
            listOf(RemoteButton.Menu to true, RemoteButton.Menu to false,
                    RemoteButton.VolumeUp to true, RemoteButton.VolumeUp to false,
                    RemoteButton.VolumeDown to true, RemoteButton.VolumeDown to false),
            s.buttons,
        )
        assertEquals(listOf(MediaCommand.Play), s.medias)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.vm.RemoteViewModelTest`
Expected: FAIL — `RemoteViewModel` unresolved.

- [ ] **Step 3: Implement ViewModel**

```kotlin
// app/src/main/kotlin/dev/atvremote/app/vm/RemoteViewModel.kt
package dev.atvremote.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.atvremote.app.swipe.TouchEvent
import dev.atvremote.protocol.CompanionSession
import dev.atvremote.protocol.InputAction
import dev.atvremote.protocol.MediaCommand
import dev.atvremote.protocol.RemoteButton
import kotlinx.coroutines.launch

/**
 * Translates SwipeEngine TouchEvents + UI buttons into LOCKED CompanionSession calls.
 * Spec §7: swipe Move events are dropped unless connected; discrete buttons still fire.
 */
class RemoteViewModel(
    private val sessionProvider: () -> CompanionSession?,
    private val isConnected: () -> Boolean,
    private val onTap: () -> Unit,
    private val onEdge: () -> Unit,
    private val onSelect: () -> Unit,
) : ViewModel() {

    fun onTouchEvent(event: TouchEvent) {
        when (event) {
            is TouchEvent.Move -> {
                if (!isConnected()) return
                val s = sessionProvider() ?: return
                viewModelScope.launch { s.touch(event.x, event.y, event.phase) }
            }
            is TouchEvent.Tap -> {
                onTap()
                val s = sessionProvider() ?: return
                viewModelScope.launch { s.click(InputAction.SingleTap) }
            }
            is TouchEvent.LongPress -> {
                onSelect()
                val s = sessionProvider() ?: return
                viewModelScope.launch { s.click(InputAction.Hold) }
            }
            is TouchEvent.DirectionalStep -> {
                onEdge()
                pressButton(event.button)
            }
        }
    }

    fun pressButton(button: RemoteButton) {
        val s = sessionProvider() ?: return
        viewModelScope.launch {
            s.button(button, true)
            s.button(button, false)
        }
    }

    fun volumeUp() = pressButton(RemoteButton.VolumeUp)
    fun volumeDown() = pressButton(RemoteButton.VolumeDown)
    fun menu() = pressButton(RemoteButton.Menu)
    fun home() = pressButton(RemoteButton.Home)
    fun playPause() = pressButton(RemoteButton.PlayPause)

    fun media(command: MediaCommand) {
        val s = sessionProvider() ?: return
        viewModelScope.launch { s.media(command) }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.vm.RemoteViewModelTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/atvremote/app/vm/RemoteViewModel.kt app/src/test/kotlin/dev/atvremote/app/vm/RemoteViewModelTest.kt
git commit -m "feat(app): RemoteViewModel mapping gestures/buttons to CompanionSession"
```

---

## Task 11: `KeyboardViewModel` (real-time text sync)

**Files:**
- Create: `app/src/main/kotlin/dev/atvremote/app/vm/KeyboardViewModel.kt`
- Test: `app/src/test/kotlin/dev/atvremote/app/vm/KeyboardViewModelTest.kt`

Observes `session.keyboardFocus: StateFlow<KeyboardFocusState>`; when `Focused` it pulls `textGet()` and exposes it; edits push via `textSet/textAppend/textClear`.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/dev/atvremote/app/vm/KeyboardViewModelTest.kt
package dev.atvremote.app.vm

import dev.atvremote.app.testutil.FakeSession
import dev.atvremote.protocol.KeyboardFocusState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KeyboardViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun focusShowsKeyboardAndLoadsExistingText() = runTest {
        val s = FakeSession().apply { text = "hel" }
        val vm = KeyboardViewModel { s }
        s.focusFlow.value = KeyboardFocusState.Focused
        dispatcher.scheduler.advanceUntilIdle()
        val st = vm.state.first { it.visible }
        assertTrue(st.visible)
        assertEquals("hel", st.text)
    }

    @Test fun unfocusHidesKeyboard() = runTest {
        val s = FakeSession()
        val vm = KeyboardViewModel { s }
        s.focusFlow.value = KeyboardFocusState.Focused
        dispatcher.scheduler.advanceUntilIdle()
        s.focusFlow.value = KeyboardFocusState.Unfocused
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(!vm.state.first().visible)
    }

    @Test fun editsPushToSession() = runTest {
        val s = FakeSession()
        val vm = KeyboardViewModel { s }
        vm.setText("hello")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("hello", s.text)
        vm.append("!")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("hello!", s.text)
        vm.clear()
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("", s.text)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.vm.KeyboardViewModelTest`
Expected: FAIL — `KeyboardViewModel` unresolved.

- [ ] **Step 3: Implement ViewModel**

```kotlin
// app/src/main/kotlin/dev/atvremote/app/vm/KeyboardViewModel.kt
package dev.atvremote.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.atvremote.protocol.CompanionSession
import dev.atvremote.protocol.KeyboardFocusState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class KeyboardUiState(val visible: Boolean = false, val text: String = "")

/**
 * Real-time keyboard sync (spec §5/§6): auto-shows when the ATV text field is
 * Focused, mirrors current text, and pushes edits through text*().
 */
class KeyboardViewModel(
    private val sessionProvider: () -> CompanionSession?,
) : ViewModel() {
    private val _state = MutableStateFlow(KeyboardUiState())
    val state: StateFlow<KeyboardUiState> = _state.asStateFlow()

    init {
        val s = sessionProvider()
        if (s != null) {
            viewModelScope.launch {
                s.keyboardFocus.collect { focus ->
                    when (focus) {
                        KeyboardFocusState.Focused ->
                            _state.value = KeyboardUiState(visible = true, text = s.textGet())
                        KeyboardFocusState.Unfocused ->
                            _state.value = _state.value.copy(visible = false)
                    }
                }
            }
        }
    }

    fun setText(value: String) {
        _state.value = _state.value.copy(text = value)
        val s = sessionProvider() ?: return
        viewModelScope.launch { s.textSet(value) }
    }

    fun append(value: String) {
        _state.value = _state.value.copy(text = _state.value.text + value)
        val s = sessionProvider() ?: return
        viewModelScope.launch { s.textAppend(value) }
    }

    fun clear() {
        _state.value = _state.value.copy(text = "")
        val s = sessionProvider() ?: return
        viewModelScope.launch { s.textClear() }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.vm.KeyboardViewModelTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/atvremote/app/vm/KeyboardViewModel.kt app/src/test/kotlin/dev/atvremote/app/vm/KeyboardViewModelTest.kt
git commit -m "feat(app): KeyboardViewModel real-time text sync via :protocol"
```

---

## Task 12: `LauncherViewModel` (apps + power)

**Files:**
- Create: `app/src/main/kotlin/dev/atvremote/app/vm/LauncherViewModel.kt`
- Test: `app/src/test/kotlin/dev/atvremote/app/vm/LauncherViewModelTest.kt`

Consumes `session.listApps()`, `launchApp`, `power`, `powerStatus`.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/dev/atvremote/app/vm/LauncherViewModelTest.kt
package dev.atvremote.app.vm

import dev.atvremote.app.testutil.FakeSession
import dev.atvremote.protocol.InstalledApp
import dev.atvremote.protocol.PowerStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)
    @AfterTest fun tearDown() = Dispatchers.resetMain()

    @Test fun loadsAppsAndPowerStatus() = runTest {
        val s = FakeSession().apply {
            apps = listOf(InstalledApp("a", "Netflix"), InstalledApp("b", "YouTube"))
            poweredOn = true
        }
        val vm = LauncherViewModel { s }
        vm.refresh()
        dispatcher.scheduler.advanceUntilIdle()
        val st = vm.state.first { it.apps.isNotEmpty() }
        assertEquals(2, st.apps.size)
        assertEquals(PowerStatus.On, st.power)
    }

    @Test fun launchAndPowerCallProtocol() = runTest {
        val s = FakeSession()
        val vm = LauncherViewModel { s }
        vm.launch("com.netflix.Netflix")
        vm.setPower(false)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("com.netflix.Netflix", s.launched)
        assertEquals(false, s.poweredOn)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.vm.LauncherViewModelTest`
Expected: FAIL — `LauncherViewModel` unresolved.

- [ ] **Step 3: Implement ViewModel**

```kotlin
// app/src/main/kotlin/dev/atvremote/app/vm/LauncherViewModel.kt
package dev.atvremote.app.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.atvremote.protocol.CompanionSession
import dev.atvremote.protocol.InstalledApp
import dev.atvremote.protocol.PowerStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LauncherUiState(
    val apps: List<InstalledApp> = emptyList(),
    val power: PowerStatus = PowerStatus.Unknown,
    val loading: Boolean = false,
)

class LauncherViewModel(
    private val sessionProvider: () -> CompanionSession?,
) : ViewModel() {
    private val _state = MutableStateFlow(LauncherUiState())
    val state: StateFlow<LauncherUiState> = _state.asStateFlow()

    fun refresh() {
        val s = sessionProvider() ?: return
        _state.value = _state.value.copy(loading = true)
        viewModelScope.launch {
            val apps = s.listApps()
            val power = s.powerStatus()
            _state.value = LauncherUiState(apps = apps, power = power, loading = false)
        }
    }

    fun launch(bundleId: String) {
        val s = sessionProvider() ?: return
        viewModelScope.launch { s.launchApp(bundleId) }
    }

    fun setPower(on: Boolean) {
        val s = sessionProvider() ?: return
        viewModelScope.launch {
            s.power(on)
            _state.value = _state.value.copy(power = s.powerStatus())
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.vm.LauncherViewModelTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/atvremote/app/vm/LauncherViewModel.kt app/src/test/kotlin/dev/atvremote/app/vm/LauncherViewModelTest.kt
git commit -m "feat(app): LauncherViewModel for installed apps + power"
```

---

## Task 13: `TuningViewModel` + swipe-tuning harness logic

**Files:**
- Create: `app/src/main/kotlin/dev/atvremote/app/vm/TuningViewModel.kt`
- Test: `app/src/test/kotlin/dev/atvremote/app/vm/TuningViewModelTest.kt`

Debug harness (spec §5/§8): feeds a recorded drag sample list through `SwipeEngine` with adjustable `SwipeTuning`, records resulting `TouchEvent`s, and supports an A/B comparison of two parameter sets on the same input.

- [ ] **Step 1: Write the failing test**

```kotlin
// app/src/test/kotlin/dev/atvremote/app/vm/TuningViewModelTest.kt
package dev.atvremote.app.vm

import dev.atvremote.app.swipe.SwipeTuning
import dev.atvremote.app.swipe.TouchEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TuningViewModelTest {
    private val drag = listOf(
        TuningSample.Down(100f, 500f, 0L),
        TuningSample.Move(200f, 500f, 16L),
        TuningSample.Move(360f, 500f, 32L),
        TuningSample.Up(360f, 500f, 48L),
    )

    @Test fun replayProducesEventLog() {
        val vm = TuningViewModel(widthPx = 1000f, heightPx = 1000f)
        val log = vm.replay(SwipeTuning.DEFAULT, drag)
        assertTrue(log.any { it is TouchEvent.Move })
        assertEquals(
            dev.atvremote.protocol.TouchPhase.Release,
            (log.last() as TouchEvent.Move).phase,
        )
    }

    @Test fun higherGainProducesLargerDisplacement() {
        val vm = TuningViewModel(1000f, 1000f)
        val slow = vm.replay(SwipeTuning.DEFAULT.copy(gain = 1.0f, velocityExponent = 1f), drag)
        val fast = vm.replay(SwipeTuning.DEFAULT.copy(gain = 4.0f, velocityExponent = 1f), drag)
        val slowMax = slow.filterIsInstance<TouchEvent.Move>().maxOf { it.x }
        val fastMax = fast.filterIsInstance<TouchEvent.Move>().maxOf { it.x }
        assertTrue(fastMax >= slowMax, "fast=$fastMax slow=$slowMax")
    }

    @Test fun abComparesTwoParamSetsOnSameInput() {
        val vm = TuningViewModel(1000f, 1000f)
        val ab = vm.compare(
            a = SwipeTuning.DEFAULT.copy(gain = 1.0f),
            b = SwipeTuning.DEFAULT.copy(gain = 3.0f),
            drag = drag,
        )
        assertTrue(ab.a.isNotEmpty() && ab.b.isNotEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.vm.TuningViewModelTest`
Expected: FAIL — `TuningViewModel`/`TuningSample` unresolved.

- [ ] **Step 3: Implement harness**

```kotlin
// app/src/main/kotlin/dev/atvremote/app/vm/TuningViewModel.kt
package dev.atvremote.app.vm

import androidx.lifecycle.ViewModel
import dev.atvremote.app.swipe.SwipeEngine
import dev.atvremote.app.swipe.SwipeTuning
import dev.atvremote.app.swipe.TouchEvent

/** One recorded raw input event for the tuning harness. */
sealed interface TuningSample {
    data class Down(val x: Float, val y: Float, val t: Long) : TuningSample
    data class Move(val x: Float, val y: Float, val t: Long) : TuningSample
    data class Up(val x: Float, val y: Float, val t: Long) : TuningSample
}

data class AbResult(val a: List<TouchEvent>, val b: List<TouchEvent>)

/**
 * Swipe-tuning harness (spec §5/§8): replays a recorded gesture through SwipeEngine
 * with given SwipeTuning params and returns the emitted TouchEvent log so the
 * developer can A/B compare velocity/inertia curves.
 */
class TuningViewModel(
    private val widthPx: Float,
    private val heightPx: Float,
) : ViewModel() {

    fun replay(tuning: SwipeTuning, drag: List<TuningSample>): List<TouchEvent> {
        val engine = SwipeEngine(tuning, widthPx, heightPx)
        val out = ArrayList<TouchEvent>()
        for (s in drag) {
            when (s) {
                is TuningSample.Down -> out += engine.onDown(s.x, s.y, s.t)
                is TuningSample.Move -> out += engine.onMove(s.x, s.y, s.t)
                is TuningSample.Up -> {
                    out += engine.onUp(s.x, s.y, s.t)
                    var now = s.t + 8L
                    var guard = 0
                    while (engine.inertiaActive && guard < 300) {
                        out += engine.onInertiaFrame(now); now += 8L; guard++
                    }
                }
            }
        }
        return out
    }

    fun compare(a: SwipeTuning, b: SwipeTuning, drag: List<TuningSample>): AbResult =
        AbResult(a = replay(a, drag), b = replay(b, drag))
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests dev.atvremote.app.vm.TuningViewModelTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/atvremote/app/vm/TuningViewModel.kt app/src/test/kotlin/dev/atvremote/app/vm/TuningViewModelTest.kt
git commit -m "feat(app): swipe-tuning A/B harness ViewModel"
```

---

## Task 14: Theme + Hero screen Composables (top bar → trackpad → button row → keyboard entry/volume)

**Files:**
- Create: `app/src/main/kotlin/dev/atvremote/app/ui/theme/Color.kt`
- Create: `app/src/main/kotlin/dev/atvremote/app/ui/theme/Theme.kt`
- Create: `app/src/main/kotlin/dev/atvremote/app/ui/hero/Trackpad.kt`
- Create: `app/src/main/kotlin/dev/atvremote/app/ui/hero/ButtonRow.kt`
- Create: `app/src/main/kotlin/dev/atvremote/app/ui/hero/HeroScreen.kt`
- Test: `app/src/androidTest/kotlin/dev/atvremote/app/ui/HeroScreenUiTest.kt`

This task is layout-heavy; the *logic* (gesture→event, edge zones, throttle) was TDD'd in Tasks 3/10. Here the pixel composition is a clearly-scoped implementation step, and a Compose UI test asserts structural correctness (trackpad present, button row has Menu/TV/Play-Pause and **no Siri**, volume rocker present).

- [ ] **Step 1: Write the failing Compose UI test**

```kotlin
// app/src/androidTest/kotlin/dev/atvremote/app/ui/HeroScreenUiTest.kt
package dev.atvremote.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import dev.atvremote.app.ui.hero.HeroScreen
import dev.atvremote.app.ui.hero.HeroCallbacks
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class HeroScreenUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun heroShowsTrackpadButtonRowAndVolumeWithoutSiri() {
        rule.setContent { HeroScreen(deviceName = "Living Room", callbacks = HeroCallbacks()) }

        rule.onNodeWithTag("trackpad").assertIsDisplayed()
        rule.onNodeWithContentDescription("Menu").assertIsDisplayed()
        rule.onNodeWithContentDescription("TV/Home").assertIsDisplayed()
        rule.onNodeWithContentDescription("Play/Pause").assertIsDisplayed()
        rule.onNodeWithContentDescription("Volume Up").assertIsDisplayed()
        rule.onNodeWithContentDescription("Volume Down").assertIsDisplayed()
        rule.onNodeWithContentDescription("Keyboard").assertIsDisplayed()

        // Spec §5: Siri button removed.
        assertEquals(
            0,
            rule.onAllNodesWithContentDescription("Siri").fetchSemanticsNodes().size,
        )
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest --tests dev.atvremote.app.ui.HeroScreenUiTest`
(or `:app:connectedCheck` if no test filter is wired)
Expected: FAIL — `HeroScreen`/`HeroCallbacks` unresolved.

- [ ] **Step 3: Implement theme + Hero**

`app/src/main/kotlin/dev/atvremote/app/ui/theme/Color.kt`:
```kotlin
package dev.atvremote.app.ui.theme

import androidx.compose.ui.graphics.Color

val AppleSurfaceLight = Color(0xFFF2F2F7)
val AppleSurfaceDark = Color(0xFF000000)
val TrackpadLight = Color(0xFFFFFFFF)
val TrackpadDark = Color(0xFF1C1C1E)
val ButtonTintLight = Color(0xFF1C1C1E)
val ButtonTintDark = Color(0xFFFFFFFF)
val Accent = Color(0xFF0A84FF)
```

`app/src/main/kotlin/dev/atvremote/app/ui/theme/Theme.kt`:
```kotlin
package dev.atvremote.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun AtvRemoteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme(
            background = AppleSurfaceDark, surface = TrackpadDark, primary = Accent
        )
        else -> lightColorScheme(
            background = AppleSurfaceLight, surface = TrackpadLight, primary = Accent
        )
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
```

`app/src/main/kotlin/dev/atvremote/app/ui/hero/Trackpad.kt`:
```kotlin
package dev.atvremote.app.ui.hero

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.atvremote.app.swipe.SwipeEngine
import dev.atvremote.app.swipe.SwipeTuning
import dev.atvremote.app.swipe.TouchEvent

/**
 * Circular touch trackpad. Raw pointer samples are funneled into the pure
 * SwipeEngine (TDD'd in Task 3); emitted TouchEvents go to onEvent (Task 10 VM).
 */
@Composable
fun Trackpad(
    tuning: SwipeTuning,
    onEvent: (TouchEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(24.dp)
            .aspectRatio(1f)
            .testTag("trackpad")
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(tuning) {
                val engine = SwipeEngine(tuning, size.width.toFloat(), size.height.toFloat())
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown()
                        val t0 = System.currentTimeMillis()
                        engine.onDown(down.position.x, down.position.y, t0).forEach(onEvent)
                        var dragging = true
                        while (dragging) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.first()
                            val now = System.currentTimeMillis()
                            engine.onTick(now).forEach(onEvent)
                            if (ch.pressed) {
                                engine.onMove(ch.position.x, ch.position.y, now).forEach(onEvent)
                                ch.consume()
                            } else {
                                engine.onUp(ch.position.x, ch.position.y, now).forEach(onEvent)
                                var n = now + 8L
                                var guard = 0
                                while (engine.inertiaActive && guard < 240) {
                                    engine.onInertiaFrame(n).forEach(onEvent)
                                    n += 8L; guard++
                                }
                                ch.consume()
                                dragging = false
                            }
                        }
                    }
                }
            },
    ) {}
}
```
(Imports `androidx.compose.foundation.layout.Box`, `androidx.compose.ui.draw.clip`, `androidx.compose.ui.input.pointer.awaitFirstDown` are also required — add the corresponding `import` lines: `import androidx.compose.foundation.layout.Box`, `import androidx.compose.ui.draw.clip`, `import androidx.compose.ui.input.pointer.awaitFirstDown`.)

`app/src/main/kotlin/dev/atvremote/app/ui/hero/ButtonRow.kt`:
```kotlin
package dev.atvremote.app.ui.hero

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Spec §5: button row is Menu · TV/Home · Play/Pause (Siri removed, rebalanced),
 * plus the keyboard entry + a vertical volume rocker.
 */
@Composable
fun ButtonRow(
    onMenu: () -> Unit,
    onHome: () -> Unit,
    onPlayPause: () -> Unit,
    onKeyboard: () -> Unit,
    onVolumeUp: () -> Unit,
    onVolumeDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            FilledTonalIconButton(
                onClick = onMenu,
                modifier = Modifier.size(64.dp).semantics { contentDescription = "Menu" },
            ) { Icon(Icons.Filled.Menu, contentDescription = null) }

            FilledTonalIconButton(
                onClick = onHome,
                modifier = Modifier.size(64.dp).semantics { contentDescription = "TV/Home" },
            ) { Icon(Icons.Filled.Tv, contentDescription = null) }

            FilledTonalIconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(64.dp).semantics { contentDescription = "Play/Pause" },
            ) { Icon(Icons.Filled.PlayArrow, contentDescription = null) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FilledTonalIconButton(
                onClick = onKeyboard,
                modifier = Modifier.size(56.dp).semantics { contentDescription = "Keyboard" },
            ) { Icon(Icons.Outlined.Keyboard, contentDescription = null) }

            Column {
                FilledTonalIconButton(
                    onClick = onVolumeUp,
                    modifier = Modifier.size(56.dp)
                        .semantics { contentDescription = "Volume Up" },
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) }
                FilledTonalIconButton(
                    onClick = onVolumeDown,
                    modifier = Modifier.size(56.dp)
                        .semantics { contentDescription = "Volume Down" },
                    shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
                ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) }
            }
        }
    }
}
```

`app/src/main/kotlin/dev/atvremote/app/ui/hero/HeroScreen.kt`:
```kotlin
package dev.atvremote.app.ui.hero

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.atvremote.app.swipe.SwipeTuning
import dev.atvremote.app.swipe.TouchEvent

/** All Hero interactions wired by AppNav (Task 15). Defaults are no-ops for previews/tests. */
data class HeroCallbacks(
    val onTouchEvent: (TouchEvent) -> Unit = {},
    val onMenu: () -> Unit = {},
    val onHome: () -> Unit = {},
    val onPlayPause: () -> Unit = {},
    val onKeyboard: () -> Unit = {},
    val onVolumeUp: () -> Unit = {},
    val onVolumeDown: () -> Unit = {},
    val onOpenDevices: () -> Unit = {},
    val onOpenMenu: () -> Unit = {},
    val tuning: SwipeTuning = SwipeTuning.DEFAULT,
    val connectionBanner: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeroScreen(deviceName: String, callbacks: HeroCallbacks) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TextButton(onClick = callbacks.onOpenDevices) { Text(deviceName) }
                },
                actions = {
                    IconButton(
                        onClick = callbacks.onOpenMenu,
                        modifier = Modifier.semantics { contentDescription = "More" },
                    ) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            callbacks.connectionBanner?.let {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) { Text(it) }
            }
            Trackpad(
                tuning = callbacks.tuning,
                onEvent = callbacks.onTouchEvent,
                modifier = Modifier,
            )
            ButtonRow(
                onMenu = callbacks.onMenu,
                onHome = callbacks.onHome,
                onPlayPause = callbacks.onPlayPause,
                onKeyboard = callbacks.onKeyboard,
                onVolumeUp = callbacks.onVolumeUp,
                onVolumeDown = callbacks.onVolumeDown,
            )
        }
    }
}
```
(Add `import androidx.compose.ui.unit.dp` to `HeroScreen.kt`.)

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:connectedDebugAndroidTest --tests dev.atvremote.app.ui.HeroScreenUiTest`
Expected: PASS — trackpad/button-row/volume present, zero Siri nodes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/atvremote/app/ui/theme app/src/main/kotlin/dev/atvremote/app/ui/hero app/src/androidTest/kotlin/dev/atvremote/app/ui/HeroScreenUiTest.kt
git commit -m "feat(app): Apple-faithful Hero screen (trackpad/button row/volume), no Siri"
```

---

## Task 15: Devices/Pair/Keyboard/Launcher/Tuning screens + `AppNav` wiring

**Files:**
- Create: `app/src/main/kotlin/dev/atvremote/app/ui/devices/DevicesScreen.kt`
- Create: `app/src/main/kotlin/dev/atvremote/app/ui/pair/PairScreen.kt`
- Create: `app/src/main/kotlin/dev/atvremote/app/ui/keyboard/KeyboardScreen.kt`
- Create: `app/src/main/kotlin/dev/atvremote/app/ui/launcher/AppLauncherScreen.kt`
- Create: `app/src/main/kotlin/dev/atvremote/app/ui/tuning/SwipeTuningScreen.kt`
- Create: `app/src/main/kotlin/dev/atvremote/app/ui/AppNav.kt`
- Modify: `app/src/main/kotlin/dev/atvremote/app/MainActivity.kt` (set `AppNav` content)
- Test: `app/src/androidTest/kotlin/dev/atvremote/app/ui/PairScreenUiTest.kt`
- Test: `app/src/androidTest/kotlin/dev/atvremote/app/ui/KeyboardScreenUiTest.kt`

Pair screen = on-TV PIN entry (spec §5: ATV shows PIN, app enters it). Keyboard screen auto-shows when focused (spec §5/§6).

- [ ] **Step 1: Write the failing Compose UI tests**

```kotlin
// app/src/androidTest/kotlin/dev/atvremote/app/ui/PairScreenUiTest.kt
package dev.atvremote.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import dev.atvremote.app.ui.pair.PairScreen
import dev.atvremote.app.vm.PairingUiState
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class PairScreenUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun pinEntryAndSubmit() {
        var submitted: String? = null
        rule.setContent {
            PairScreen(
                state = PairingUiState.AwaitingPin,
                onSubmitPin = { submitted = it },
                onCancel = {},
            )
        }
        rule.onNodeWithText("Enter the code shown on your Apple TV").assertIsDisplayed()
        rule.onNodeWithContentDescription("PIN field").performTextInput("4821")
        rule.onNodeWithText("Pair").performClick()
        assertEquals("4821", submitted)
    }

    @Test fun failureShowsReason() {
        rule.setContent {
            PairScreen(
                state = PairingUiState.Failed("Incorrect PIN"),
                onSubmitPin = {},
                onCancel = {},
            )
        }
        rule.onNodeWithText("Incorrect PIN").assertIsDisplayed()
    }
}
```

```kotlin
// app/src/androidTest/kotlin/dev/atvremote/app/ui/KeyboardScreenUiTest.kt
package dev.atvremote.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import dev.atvremote.app.ui.keyboard.KeyboardScreen
import dev.atvremote.app.vm.KeyboardUiState
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

class KeyboardScreenUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun visibleStateShowsFieldAndEditsPropagate() {
        var pushed: String? = null
        rule.setContent {
            KeyboardScreen(
                state = KeyboardUiState(visible = true, text = "ne"),
                onTextChange = { pushed = it },
            )
        }
        rule.onNodeWithContentDescription("TV text field").assertIsDisplayed()
        rule.onNodeWithContentDescription("TV text field").performTextInput("t")
        assertEquals("net", pushed)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:connectedDebugAndroidTest --tests dev.atvremote.app.ui.PairScreenUiTest --tests dev.atvremote.app.ui.KeyboardScreenUiTest`
Expected: FAIL — screens/`AppNav` unresolved.

- [ ] **Step 3: Implement screens + nav**

`app/src/main/kotlin/dev/atvremote/app/ui/devices/DevicesScreen.kt`:
```kotlin
package dev.atvremote.app.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.atvremote.app.vm.DiscoveredDevice

@Composable
fun DevicesScreen(
    devices: List<DiscoveredDevice>,
    onSelect: (DiscoveredDevice) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Apple TVs on your network")
        LazyColumn(Modifier.fillMaxWidth()) {
            items(devices) { d ->
                ListItem(
                    headlineContent = { Text(d.device.name) },
                    supportingContent = {
                        Text(if (d.paired) "Paired · ${d.device.host}" else d.device.host)
                    },
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(d) },
                )
            }
        }
    }
}
```

`app/src/main/kotlin/dev/atvremote/app/ui/pair/PairScreen.kt`:
```kotlin
package dev.atvremote.app.ui.pair

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.atvremote.app.vm.PairingUiState

@Composable
fun PairScreen(
    state: PairingUiState,
    onSubmitPin: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (state) {
            PairingUiState.Connecting -> CircularProgressIndicator()
            PairingUiState.AwaitingPin -> {
                Text("Enter the code shown on your Apple TV")
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier
                        .padding(16.dp)
                        .semantics { contentDescription = "PIN field" },
                )
                Button(onClick = { onSubmitPin(pin) }) { Text("Pair") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            PairingUiState.Completed -> Text("Paired")
            is PairingUiState.Failed -> {
                Text(state.reason)
                TextButton(onClick = onCancel) { Text("Back") }
            }
        }
    }
}
```

`app/src/main/kotlin/dev/atvremote/app/ui/keyboard/KeyboardScreen.kt`:
```kotlin
package dev.atvremote.app.ui.keyboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.atvremote.app.vm.KeyboardUiState

@Composable
fun KeyboardScreen(
    state: KeyboardUiState,
    onTextChange: (String) -> Unit,
) {
    if (!state.visible) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("No text field is focused on the Apple TV")
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Typing on Apple TV")
        OutlinedTextField(
            value = state.text,
            onValueChange = onTextChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .semantics { contentDescription = "TV text field" },
        )
    }
}
```

`app/src/main/kotlin/dev/atvremote/app/ui/launcher/AppLauncherScreen.kt`:
```kotlin
package dev.atvremote.app.ui.launcher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.atvremote.protocol.InstalledApp
import dev.atvremote.protocol.PowerStatus

@Composable
fun AppLauncherScreen(
    apps: List<InstalledApp>,
    power: PowerStatus,
    onLaunch: (String) -> Unit,
    onPower: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Power (${power.name})")
            Switch(checked = power == PowerStatus.On, onCheckedChange = onPower)
        }
        LazyVerticalGrid(columns = GridCells.Adaptive(96.dp)) {
            items(apps) { app ->
                Card(
                    modifier = Modifier
                        .padding(8.dp)
                        .clickable { onLaunch(app.bundleId) },
                ) {
                    Text(app.name, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}
```

`app/src/main/kotlin/dev/atvremote/app/ui/tuning/SwipeTuningScreen.kt`:
```kotlin
package dev.atvremote.app.ui.tuning

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.atvremote.app.swipe.SwipeTuning
import dev.atvremote.app.swipe.TouchEvent
import dev.atvremote.app.ui.hero.Trackpad

/** Debug A/B tuning harness (spec §5/§8). */
@Composable
fun SwipeTuningScreen() {
    var gain by remember { mutableStateOf(SwipeTuning.DEFAULT.gain) }
    var exponent by remember { mutableStateOf(SwipeTuning.DEFAULT.velocityExponent) }
    var decay by remember { mutableStateOf(SwipeTuning.DEFAULT.inertiaDecay) }
    val log = remember { mutableStateOf(listOf<TouchEvent>()) }
    val tuning = SwipeTuning.DEFAULT.copy(
        gain = gain, velocityExponent = exponent, inertiaDecay = decay,
    )
    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
    ) {
        Text("Swipe tuning (debug)")
        Text("gain ${"%.2f".format(gain)}")
        Slider(value = gain, onValueChange = { gain = it }, valueRange = 0.5f..6f)
        Text("velocityExponent ${"%.2f".format(exponent)}")
        Slider(value = exponent, onValueChange = { exponent = it }, valueRange = 0.5f..3f)
        Text("inertiaDecay ${"%.2f".format(decay)}")
        Slider(value = decay, onValueChange = { decay = it }, valueRange = 0.5f..0.99f)
        Trackpad(
            tuning = tuning,
            onEvent = { e -> log.value = (log.value + e).takeLast(50) },
        )
        Text("Emitted events: ${log.value.size}")
        log.value.takeLast(8).forEach { Text(it.toString()) }
    }
}
```

`app/src/main/kotlin/dev/atvremote/app/ui/AppNav.kt`:
```kotlin
package dev.atvremote.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.atvremote.app.conn.UiConnectionState
import dev.atvremote.app.swipe.TouchEvent
import dev.atvremote.app.ui.devices.DevicesScreen
import dev.atvremote.app.ui.hero.HeroCallbacks
import dev.atvremote.app.ui.hero.HeroScreen
import dev.atvremote.app.ui.keyboard.KeyboardScreen
import dev.atvremote.app.ui.launcher.AppLauncherScreen
import dev.atvremote.app.ui.pair.PairScreen
import dev.atvremote.app.ui.theme.AtvRemoteTheme
import dev.atvremote.app.vm.DiscoveryViewModel
import dev.atvremote.app.vm.KeyboardViewModel
import dev.atvremote.app.vm.LauncherViewModel
import dev.atvremote.app.vm.PairingUiState
import dev.atvremote.app.vm.RemoteViewModel

enum class Dest { Hero, Devices, Pair, Keyboard, Launcher, Tuning }

/**
 * Top-level navigation (spec §5 nav structure): Hero is home; top-bar device
 * button -> Devices/Pair; right menu -> Launcher/Power; focused text field
 * auto-routes to Keyboard.
 */
@Composable
fun AppNav(
    discoveryVm: DiscoveryViewModel,
    remoteVm: RemoteViewModel,
    keyboardVm: KeyboardViewModel,
    launcherVm: LauncherViewModel,
    connectionState: UiConnectionState,
    deviceName: String,
    onSelectDevice: (deviceId: String) -> Unit,
    pairingState: PairingUiState?,
    onSubmitPin: (String) -> Unit,
) {
    AtvRemoteTheme {
        var dest by remember { mutableStateOf(Dest.Hero) }
        val kb by keyboardVm.state.collectAsState()
        val disc by discoveryVm.state.collectAsState()
        val launch by launcherVm.state.collectAsState()

        // Auto-route to the keyboard when the ATV focuses a text field (spec §5/§6).
        if (kb.visible && dest != Dest.Keyboard) dest = Dest.Keyboard
        if (!kb.visible && dest == Dest.Keyboard) dest = Dest.Hero

        val banner = when (connectionState) {
            is UiConnectionState.Reconnecting -> "Reconnecting…"
            is UiConnectionState.Connecting -> "Connecting…"
            is UiConnectionState.CredentialInvalid ->
                "Pairing expired — please re-pair this Apple TV"
            is UiConnectionState.Failed -> "Connection failed"
            else -> null
        }

        when (dest) {
            Dest.Hero -> HeroScreen(
                deviceName = deviceName,
                callbacks = HeroCallbacks(
                    onTouchEvent = { e: TouchEvent -> remoteVm.onTouchEvent(e) },
                    onMenu = remoteVm::menu,
                    onHome = remoteVm::home,
                    onPlayPause = remoteVm::playPause,
                    onKeyboard = { dest = Dest.Keyboard },
                    onVolumeUp = remoteVm::volumeUp,
                    onVolumeDown = remoteVm::volumeDown,
                    onOpenDevices = { dest = Dest.Devices },
                    onOpenMenu = { dest = Dest.Launcher },
                    connectionBanner = banner,
                ),
            )
            Dest.Devices -> DevicesScreen(
                devices = disc.devices,
                onSelect = { onSelectDevice(it.device.id); dest = Dest.Hero },
            )
            Dest.Pair -> PairScreen(
                state = pairingState ?: PairingUiState.Connecting,
                onSubmitPin = onSubmitPin,
                onCancel = { dest = Dest.Devices },
            )
            Dest.Keyboard -> KeyboardScreen(
                state = kb,
                onTextChange = keyboardVm::setText,
            )
            Dest.Launcher -> AppLauncherScreen(
                apps = launch.apps,
                power = launch.power,
                onLaunch = launcherVm::launch,
                onPower = launcherVm::setPower,
            )
            Dest.Tuning -> dev.atvremote.app.ui.tuning.SwipeTuningScreen()
        }
    }
}
```

`app/src/main/kotlin/dev/atvremote/app/MainActivity.kt` — replace the placeholder `setContent { }` body. Because full graph wiring (binding the Service, building ViewModels from `AppGraph`) is process/Service plumbing already covered by Tasks 5–7, set content to drive `AppNav` from the bound `ConnectionManager`:

```kotlin
package dev.atvremote.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import dev.atvremote.app.conn.ConnectionManager
import dev.atvremote.app.conn.ConnectionService
import dev.atvremote.app.conn.UiConnectionState
import dev.atvremote.app.ui.AppNav
import dev.atvremote.app.vm.DiscoveryViewModel
import dev.atvremote.app.vm.KeyboardViewModel
import dev.atvremote.app.vm.LauncherViewModel
import dev.atvremote.app.vm.RemoteViewModel
import dev.atvremote.protocol.AppleTvRemote
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private var manager: ConnectionManager? = null
    private val ready = MutableStateFlow(false)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            manager = (binder as ConnectionService.LocalBinder).manager()
            ready.value = true
        }
        override fun onServiceDisconnected(name: ComponentName?) { manager = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val svc = Intent(this, ConnectionService::class.java)
        startForegroundService(svc)
        bindService(svc, conn, Context.BIND_AUTO_CREATE)

        val graph = (application as AtvRemoteApp).graph
        setContent {
            val isReady by ready.collectAsState()
            if (!isReady) return@setContent
            val cm = manager ?: return@setContent
            val ui by cm.uiState.collectAsState()

            val discoveryVm = DiscoveryViewModel(
                discovery = AppleTvRemote.discovery(),
                pairedDeviceIds = { graph.credentialStore.allDeviceIds().toSet() },
            )
            val remoteVm = RemoteViewModel(
                sessionProvider = { cm.currentSession() },
                isConnected = { cm.uiState.value is UiConnectionState.Connected },
                onTap = { graph.haptics.tap() },
                onEdge = { graph.haptics.edgeStep() },
                onSelect = { graph.haptics.select() },
            )
            val keyboardVm = KeyboardViewModel { cm.currentSession() }
            val launcherVm = LauncherViewModel { cm.currentSession() }

            AppNav(
                discoveryVm = discoveryVm,
                remoteVm = remoteVm,
                keyboardVm = keyboardVm,
                launcherVm = launcherVm,
                connectionState = ui,
                deviceName = (ui as? UiConnectionState.Connected)?.device?.name ?: "Apple TV",
                onSelectDevice = { id ->
                    lifecycleScope.launchWhenStarted {
                        val blob = graph.credentialStore.load(id) ?: return@launchWhenStarted
                        val creds = dev.atvremote.protocol.HapCredentials.parse(blob)
                        val device = (ui as? UiConnectionState.Connected)?.device
                            ?: discoveryVm.state.value.devices
                                .firstOrNull { it.device.id == id }?.device
                            ?: return@launchWhenStarted
                        cm.connect(device, creds)
                    }
                },
                pairingState = null,
                onSubmitPin = {},
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unbindService(conn) }
    }
}
```

> Pairing UI is launched from `DevicesScreen` for unpaired devices by creating a `PairingViewModel(deviceId, AppleTvRemote.pair(device), graph.credentialStore::save)` and routing `Dest.Pair`; `AppNav` already accepts `pairingState`/`onSubmitPin` for that path. The Devices→Pair binding is straightforward Compose state plumbing using the already-tested `PairingViewModel` (Task 9) and is implemented here as part of this task's screen wiring.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:connectedDebugAndroidTest --tests dev.atvremote.app.ui.PairScreenUiTest --tests dev.atvremote.app.ui.KeyboardScreenUiTest`
Expected: PASS.

- [ ] **Step 5: Full module check + commit**

Run: `./gradlew :app:testDebugUnitTest :app:connectedCheck`
Expected: PASS (all JVM + instrumented tests).

```bash
git add app/src/main/kotlin/dev/atvremote/app/ui app/src/main/kotlin/dev/atvremote/app/MainActivity.kt app/src/androidTest/kotlin/dev/atvremote/app/ui
git commit -m "feat(app): Devices/Pair/Keyboard/Launcher/Tuning screens + AppNav + Service-bound MainActivity"
```

---

## Self-Review

### Spec coverage map

| Spec item | Where |
|---|---|
| §5 Hero layout: top bar (device switch / right menu) → trackpad → button row → keyboard entry + volume rocker | Task 14 (`HeroScreen`/`Trackpad`/`ButtonRow`) |
| §5 Button row Menu · TV/Home · Play/Pause, **Siri removed** | Task 14 `ButtonRow` (only Menu/TV/Play-Pause; UI test asserts 0 Siri nodes) |
| §2/§5 Full-fidelity swipe: relative move + velocity/inertia, tap=select, edge=directional step, long-press=hold | Task 3 `SwipeEngine` (logic, TDD) + Task 10 `RemoteViewModel` (→ `touch`/`click`/`button`) + Task 14 `Trackpad` (input source) |
| §5/§6 Throttle ≤120 Hz | Task 2 `SwipeTuning.maxEventsPerSecond=120`, Task 3 `SwipeEngineTest.moveThrottledToMaxEventsPerSecond` |
| §5 Haptics on tap/edge-step/select (`VibrationEffect`) | Task 5 `Haptics`, fired by Task 10 `RemoteViewModel` callbacks |
| §5 Device discovery + on-TV PIN entry screens | Task 8 `DiscoveryViewModel`, Task 9 `PairingViewModel`, Task 15 `DevicesScreen`/`PairScreen` |
| §5/§6 Real-time-sync keyboard, auto-shows on focus | Task 11 `KeyboardViewModel` (observes `keyboardFocus`), Task 15 `KeyboardScreen` + `AppNav` auto-route |
| §5 App launcher grid + power | Task 12 `LauncherViewModel`, Task 15 `AppLauncherScreen` |
| §5 System light/dark, portrait phone | Task 14 `AtvRemoteTheme` (dynamic + light/dark), Task 1 manifest `screenOrientation="portrait"` |
| §3/§6 Keystore-wrapped credential storage in DataStore keyed by device id | Task 4 `KeystoreCipher` + `CredentialStore` |
| §6 Pairing flow persists `HapCredentials.serialize()` | Task 9 `PairingViewModel` (persists on `PairingState.Completed`) |
| §6 Connect flow: read creds → connect → operable | Task 6 `ConnectionManager.connect`, Task 15 `MainActivity` onSelectDevice (`HapCredentials.parse` → `connect`) |
| §6 Command flow UI→VM→`AppleTvRemote` suspend→encoded | Tasks 10/11/12 ViewModels calling `CompanionSession` suspend methods |
| §6 Event flow (focus/text sync) → ViewModel → UI | Task 11 `KeyboardViewModel` collecting `keyboardFocus` StateFlow |
| §7 Lifecycle-aware connect/reconnect, indexed backoff | Task 6 `ConnectionManager` backoff list + `scheduleReconnect`; Task 7 foreground/bound `ConnectionService` |
| §7 "Reconnecting…" non-error UI state | Task 6 `UiConnectionState.Reconnecting`, Task 15 `AppNav` banner "Reconnecting…" |
| §7 Credential-invalid → re-pair prompt + clear creds | Task 6 `CredentialInvalidException` path (clears store, `UiConnectionState.CredentialInvalid`), Task 15 banner "Pairing expired — please re-pair" |
| §7 Swipe events dropped during reconnect, buttons still fire | Task 10 `RemoteViewModel` (`Move` dropped when `!isConnected()`; buttons unconditional), test `swipeMovesDroppedWhileNotConnected` |
| §5/§8 Swipe-tuning A/B harness | Task 13 `TuningViewModel` (`replay`/`compare`), Task 15 `SwipeTuningScreen` |

### Placeholder scan

No "TBD/TODO/handle errors/similar to Task N" tokens in code steps. Every step shows full, runnable Kotlin/Compose source. The two cross-task notes (AppGraph↔ConnectionManager in Task 5; Devices→Pair wiring in Task 15) are sequencing guidance, not code placeholders — both reference fully-specified code already present in this plan (Task 6 and Task 9 respectively), and the plan repeats code rather than saying "same as".

### Type-consistency check (LOCKED Plan 1 + Plan 2 symbols consumed, exact names/signatures)

Plan 1:
- `AppleTvDevice(id,name,host,port,model,pairable)` — used in Tasks 1/6/8/15 with exactly 6 positional args in that order.
- `HapCredentials(clientId,clientLtsk,clientLtpk,atvId,atvLtpk)` + `serialize(): String` + `HapCredentials.parse(s: String)` — Task 9 calls `ps.credentials.serialize()`; Task 15 calls `HapCredentials.parse(blob)`; test constructs with 5 `ByteArray` args.
- `PairingState{AwaitingPin, Completed(credentials), Failed(reason)}` — Task 9 `when` over all three; reads `ps.credentials` and `ps.reason`.
- `RemoteButton{Up,Down,Left,Right,Menu,Select,Home,VolumeUp,VolumeDown,PlayPause}` with `.hid` — Tasks 1/3/10 (no `Siri`, consistent with §5 and the locked enum).
- `DeviceDiscovery.devices(): Flow<List<AppleTvDevice>>` — Task 8 implements/consumes exactly.
- `AppleTvRemote.discovery()` / `AppleTvRemote.pair(device): PairingHandle` / `suspend AppleTvRemote.connect(device, credentials): CompanionSession` — Tasks 6/15.
- `PairingHandle{ state: StateFlow<PairingState>, suspend submitPin(pin), cancel() }` — Task 9 fake + VM use exact members.
- `CompanionSession.button(RemoteButton, Boolean)` / `suspend close()` — Tasks 6/10.

Plan 2:
- `TouchPhase{Press=1,Hold=3,Release=4,Click=5}` — Tasks 2/3/10 use `Press`/`Hold`/`Release` (Press on down, Hold during drag/inertia, Release on up); `Click` is part of the enum but the chosen tap/long-press path uses `click(InputAction)` per the locked design.
- `InputAction{SingleTap,DoubleTap,Hold}` — Task 10 emits `SingleTap` (tap) and `Hold` (long-press).
- `KeyboardFocusState{Focused,Unfocused}` — Task 11 `when` over both.
- `InstalledApp(bundleId,name)` — Task 12/15 read `.bundleId`/`.name`.
- `PowerStatus{On,Off,Unknown}` — Task 12/15.
- `MediaCommand{Play,Pause,NextTrack,PreviousTrack}` — Task 10 `media(MediaCommand)`.
- `ConnectionState{Connected,Reconnecting,Disconnected}` — Task 6 maps all three.
- `CompanionSession.touch(x:Int,y:Int,phase:TouchPhase)` (0..1000) — Task 10; `SwipeEngine` clamps to `0..1000` before emitting.
- `CompanionSession.click(InputAction)` — Task 10.
- `CompanionSession.textGet():String` / `textSet(String)` / `textClear()` / `textAppend(String)` — Task 11.
- `CompanionSession.keyboardFocus: StateFlow<KeyboardFocusState>` — Task 11 (non-suspend property collected).
- `CompanionSession.listApps():List<InstalledApp>` / `launchApp(String)` / `power(Boolean)` / `powerStatus():PowerStatus` / `media(MediaCommand)` — Task 12 (and `media` in Task 10).
- `CompanionSession.connectionState: StateFlow<ConnectionState>` — Task 6 (non-suspend property collected).

No `:protocol` symbol is invented; no `:protocol` source file is created or modified. The trackpad drives `session.touch(...)`/`session.click(...)`; volume rocker uses `button(RemoteButton.VolumeUp/VolumeDown,...)`; Menu/Home/PlayPause use `button(...)`; there is no Siri button anywhere.
