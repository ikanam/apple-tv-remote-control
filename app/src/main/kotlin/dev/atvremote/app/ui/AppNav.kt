package dev.atvremote.app.ui

/**
 * Navigation destinations for the Apple TV remote app.
 *
 * The launcher destination is intentionally removed per spec 2026-05-16
 * (Plan-3 Amendment A). The app-launcher subsystem is not included in Plan-3.
 *
 * The NavHost composable wiring over these five destinations is built in
 * base Task 15 (amended). This enum is the sole content of this file until
 * that task adds the NavHost + screen composables.
 */
enum class AppDestinations(val route: String) {
    HERO("hero"),
    DEVICES("devices"),
    PAIR("pair"),
    KEYBOARD("keyboard"),
    TUNING("tuning"),
}
