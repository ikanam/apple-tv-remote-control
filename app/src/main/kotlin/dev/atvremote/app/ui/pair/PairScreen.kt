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
