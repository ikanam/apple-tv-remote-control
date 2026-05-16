package dev.atvremote.app.di

import android.content.Context
import dev.atvremote.app.conn.ConnectionManager
import dev.atvremote.app.conn.MulticastLockHolder
import dev.atvremote.app.data.CredentialStore
import dev.atvremote.app.haptics.Haptics

/**
 * Hand-rolled process-wide singletons. No DI framework (YAGNI).
 *
 * credentialStore is lazy: KeystoreCipher opens AndroidKeyStore at construction;
 * deferring avoids crashing the Application before any credential access is needed
 * (and keeps Robolectric unit tests that don't touch credentials from requiring the
 * AndroidKeyStore JCA provider to be installed at app-start time).
 *
 * connectionManager (Task 6) owns the single CompanionSession. It is `by lazy`
 * (NOT built at Application.onCreate()) so the AppleTvRemote default connector and
 * the credentialStore (hence AndroidKeyStore) are only touched on first
 * connect()/Service bind. Uses ConnectionManager's DEFAULT connector
 * (`SessionConnector { d,c -> AppleTvRemote.connect(d,c) }`) — there is no
 * `remote=`/`AppleTvRemote` ctor param.
 *
 * multicastLock (S2) is lazy so no WifiManager access at app-start; S5 acquires
 * it only while the Devices screen is active.
 */
class AppGraph(appContext: Context) {
    val credentialStore by lazy { CredentialStore(appContext) }
    val haptics = Haptics(appContext)
    val connectionManager by lazy { ConnectionManager(credentialStore = credentialStore) }
    val multicastLock by lazy { MulticastLockHolder(appContext) }
}
