package com.taksi.autoaccept.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.taksi.autoaccept.core.log.LogLevel

@Composable
fun LogScreen(viewModel: MainViewModel) {
    val logs by viewModel.logs.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("${logs.size} kayıt", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = viewModel::clearLogs) { Text("Temizle") }
        }

        if (logs.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Henüz kayıt yok.\nServisi açıp taksi uygulamasına geçin;\ngelen her çağrı burada görünecek.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(logs) { log ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = log.level.container())
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                log.time,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                log.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (log.detail.isNotEmpty()) {
                            Text(log.detail, style = MaterialTheme.typography.bodySmall)
                        }
                        if (log.rawText.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                log.rawText,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun LogLevel.container(): Color = when (this) {
    LogLevel.ACCEPTED -> Color(0xFFC8E6C9)
    LogLevel.SIMULATED -> Color(0xFFFFF9C4)
    LogLevel.REJECTED -> Color(0xFFEEEEEE)
    LogLevel.INFO -> Color(0xFFE3F2FD)
    LogLevel.ERROR -> Color(0xFFFFCDD2)
}
