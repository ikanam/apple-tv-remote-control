package dev.atvremote.app.di

import android.content.Context
import dev.atvremote.app.data.CredentialStore
import dev.atvremote.app.haptics.Haptics

/**
 * Hand-rolled process-wide singletons. No DI framework (YAGNI).
 * NOTE (Plan-3 task-by-task): the `connectionManager` field + its AppleTvRemote/
 * CredentialStore wiring is added in Task 6 together with ConnectionManager itself
 * (the plan's full AppGraph references ConnectionManager which does not exist until
 * Task 6). This keeps every per-task commit compiling/green.
 *
 * credentialStore is lazy: KeystoreCipher opens AndroidKeyStore at construction;
 * deferring avoids crashing the Application before any credential access is needed
 * (and keeps Robolectric unit tests that don't touch credentials from requiring the
 * AndroidKeyStore JCA provider to be installed at app-start time).
 */
class AppGraph(appContext: Context) {
    val credentialStore by lazy { CredentialStore(appContext) }
    val haptics = Haptics(appContext)
}
