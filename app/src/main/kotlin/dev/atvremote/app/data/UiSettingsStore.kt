package dev.atvremote.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.atvremote.app.swipe.SwipeTuning
import dev.atvremote.app.ui.remote.RemoteLayoutStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.uiSettingsDataStore by preferencesDataStore(name = "atv_ui_settings")

private val LAYOUT_STYLE_KEY = stringPreferencesKey("layout_style")
private val DRAG_STEP_FRACTION_KEY = floatPreferencesKey("drag_step_fraction")

class UiSettingsStore(private val context: Context) {
    val layoutStyle: Flow<RemoteLayoutStyle> = context.uiSettingsDataStore.data.map { prefs ->
        prefs[LAYOUT_STYLE_KEY]
            ?.let { runCatching { RemoteLayoutStyle.valueOf(it) }.getOrNull() }
            ?: RemoteLayoutStyle.Physical
    }

    val dragStepFraction: Flow<Float> = context.uiSettingsDataStore.data.map { prefs ->
        prefs[DRAG_STEP_FRACTION_KEY] ?: SwipeTuning.DEFAULT.dragStepFraction
    }

    suspend fun saveLayoutStyle(style: RemoteLayoutStyle) {
        context.uiSettingsDataStore.edit { it[LAYOUT_STYLE_KEY] = style.name }
    }

    suspend fun saveDragStepFraction(fraction: Float) {
        context.uiSettingsDataStore.edit { it[DRAG_STEP_FRACTION_KEY] = fraction }
    }

    suspend fun clear() {
        context.uiSettingsDataStore.edit { it.clear() }
    }
}
