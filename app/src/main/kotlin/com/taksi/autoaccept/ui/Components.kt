package com.taksi.autoaccept.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
fun SwitchRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * Kalici bir degere bagli metin kutusu.
 *
 * Yerel metni ayri tutariz: kullanici "12," yazarken depodan gelen "12"
 * imleci geri atmasin diye. Depodaki deger disaridan degisirse yerel metin
 * yeniden esitlenir.
 */
@Composable
fun PersistedTextField(
    label: String,
    persisted: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    supporting: String? = null,
    /**
     * Yerel metnin depodaki degerle ayni anlama gelip gelmedigi.
     * Tutar alanlarinda "12," ile "12" ayni sayidir; esitlemeyi tetiklememeli.
     */
    matches: (local: String, stored: String) -> Boolean = { a, b -> a == b },
    onCommit: (String) -> Unit
) {
    var text by remember { mutableStateOf(persisted) }
    LaunchedEffect(persisted) {
        if (!matches(text, persisted)) text = persisted
    }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onCommit(it)
        },
        label = { Text(label) },
        singleLine = true,
        supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    )
}
