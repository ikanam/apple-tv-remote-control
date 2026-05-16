package dev.atvremote.app.ui.hero

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Plan-3 amendment D: physical Siri Remote layout. row1 = Back · TV/Home,
 * row2 = Play/Pause · vertical Volume rocker. No Menu/Keyboard/Mute/Siri/Apps
 * here — "Back" replaces the old "Menu" (Apple TV's Menu button IS the back
 * action; the physical remote labels it back), the Keyboard key moved to the
 * HeroScreen bottom-left (physical Mute slot, capability-gated).
 */
@Composable
fun ButtonRow(
    onBack: () -> Unit,
    onHome: () -> Unit,
    onPlayPause: () -> Unit,
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
                onClick = onBack,
                modifier = Modifier.size(64.dp).semantics { contentDescription = "Back" },
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }

            FilledTonalIconButton(
                onClick = onHome,
                modifier = Modifier.size(64.dp).semantics { contentDescription = "TV/Home" },
            ) { Icon(Icons.Filled.Tv, contentDescription = null) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FilledTonalIconButton(
                onClick = onPlayPause,
                modifier = Modifier.size(64.dp).semantics { contentDescription = "Play/Pause" },
            ) { Icon(Icons.Filled.PlayArrow, contentDescription = null) }

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
