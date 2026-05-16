package dev.atvremote.app.ui.hero

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Directional pad cluster: 3-row cross — Up (centered), Left·OK·Right, Down (centered).
 * Matches the [ButtonRow] style (FilledTonalIconButton, 64.dp). Content descriptions are
 * LOCKED: "Up", "Down", "Left", "Right", "Select" (center OK).
 */
@Composable
fun DpadRow(
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Row 1: Up
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            FilledTonalIconButton(
                onClick = onUp,
                modifier = Modifier.size(64.dp).semantics { contentDescription = "Up" },
            ) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) }
        }

        // Row 2: Left · OK (Select) · Right
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledTonalIconButton(
                onClick = onLeft,
                modifier = Modifier.size(64.dp).semantics { contentDescription = "Left" },
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null) }

            FilledTonalIconButton(
                onClick = onSelect,
                modifier = Modifier.size(64.dp).semantics { contentDescription = "Select" },
            ) { Icon(Icons.Filled.Check, contentDescription = null) }

            FilledTonalIconButton(
                onClick = onRight,
                modifier = Modifier.size(64.dp).semantics { contentDescription = "Right" },
            ) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) }
        }

        // Row 3: Down
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            FilledTonalIconButton(
                onClick = onDown,
                modifier = Modifier.size(64.dp).semantics { contentDescription = "Down" },
            ) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) }
        }
    }
}
