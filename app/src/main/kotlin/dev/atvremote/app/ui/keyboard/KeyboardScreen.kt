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
