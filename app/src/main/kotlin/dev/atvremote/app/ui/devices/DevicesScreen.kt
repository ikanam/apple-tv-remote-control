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
