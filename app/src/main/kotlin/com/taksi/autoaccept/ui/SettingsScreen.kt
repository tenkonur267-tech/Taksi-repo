package com.taksi.autoaccept.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.taksi.autoaccept.core.rules.RuleEngine
import com.taksi.autoaccept.service.AutoAcceptForegroundService
import com.taksi.autoaccept.service.RideAcceptAccessibilityService
import com.taksi.autoaccept.util.AccessibilityUtils
import com.taksi.autoaccept.util.InstalledApps

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val counters by viewModel.counters.collectAsState()
    val context = LocalContext.current

    var serviceEnabled by remember { mutableStateOf(AccessibilityUtils.isServiceEnabled(context)) }
    var serviceRunning by remember { mutableStateOf(RideAcceptAccessibilityService.instanceRunning) }
    var showAppPicker by remember { mutableStateOf(false) }

    // Kullanici sistem ayarlarindan donunce durumu tazele.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceEnabled = AccessibilityUtils.isServiceEnabled(context)
                serviceRunning = RideAcceptAccessibilityService.instanceRunning
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {

        item {
            RunControlCard(
                running = settings.enabled,
                dryRun = settings.dryRun,
                blockReason = when {
                    !serviceEnabled -> "Önce aşağıdan erişilebilirlik servisini açın."
                    settings.targetPackages.isEmpty() -> "Önce aşağıdan izlenecek uygulamayı seçin."
                    else -> null
                },
                onStart = {
                    viewModel.update { it.copy(enabled = true) }
                    AutoAcceptForegroundService.start(context)
                },
                onStop = {
                    viewModel.update { it.copy(enabled = false) }
                    AutoAcceptForegroundService.stop(context)
                }
            )
        }

        item {
            StatusCard(
                serviceEnabled = serviceEnabled,
                serviceRunning = serviceRunning,
                onOpenSettings = { AccessibilityUtils.openAccessibilitySettings(context) }
            )
        }

        item {
            SectionCard("Çalışma") {
                SwitchRow(
                    label = "Deneme modu",
                    description = "Açıkken düğmeye BASILMAZ; kararlar sadece Kayıtlar sekmesine yazılır. " +
                        "Kuralları doğrulayana kadar açık bırakın.",
                    checked = settings.dryRun,
                    onCheckedChange = { on -> viewModel.update { it.copy(dryRun = on) } }
                )
            }
        }

        item {
            SectionCard("Ekran üstü buton") {
                SwitchRow(
                    label = "Başlat/Durdur butonu ekranda dursun",
                    description = "Uygulama arka plandayken bile ekranın üstünde duran yuvarlak " +
                        "buton. Tek dokunuş başlatır/durdurur, sürükleyerek istediğiniz yere " +
                        "taşırsınız, uzun basınca bu ekran açılır.",
                    checked = settings.overlayEnabled,
                    onCheckedChange = { on -> viewModel.update { it.copy(overlayEnabled = on) } }
                )
                if (settings.overlayEnabled && !serviceEnabled) {
                    Text(
                        "Butonu erişilebilirlik servisi çiziyor; servis açılınca ekranda belirir.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        item {
            SectionCard("İzlenen uygulama") {
                if (settings.targetPackages.isEmpty()) {
                    Text(
                        "Henüz uygulama seçilmedi. Çağrıların geldiği taksi uygulamasını seçin.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    settings.targetPackages.forEach { pkg ->
                        Text(
                            "• ${InstalledApps.labelFor(context, pkg)}  ($pkg)",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    viewModel.loadApps()
                    showAppPicker = true
                }) { Text("Uygulama seç") }
            }
        }

        item {
            SectionCard("Tutar aralığı") {
                Text(
                    "Bu aralığa giren çağrılar kabul edilir. Üst sınırı boş bırakırsanız üst sınır uygulanmaz.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PersistedTextField(
                    label = "En az (TL)",
                    persisted = settings.minAmount.toFieldText(),
                    keyboardType = KeyboardType.Decimal,
                    matches = { a, b -> a.toAmountOrZero() == b.toAmountOrZero() },
                    onCommit = { text -> viewModel.update { it.copy(minAmount = text.toAmountOrZero()) } }
                )
                PersistedTextField(
                    label = "En çok (TL) — boş = sınırsız",
                    persisted = settings.maxAmount.toFieldText(),
                    keyboardType = KeyboardType.Decimal,
                    matches = { a, b -> a.toAmountOrZero() == b.toAmountOrZero() },
                    onCommit = { text -> viewModel.update { it.copy(maxAmount = text.toAmountOrZero()) } }
                )
                PersistedTextField(
                    label = "Azami mesafe (km) — boş = sınırsız",
                    persisted = settings.maxDistanceKm.toFieldText(),
                    keyboardType = KeyboardType.Decimal,
                    matches = { a, b -> a.toAmountOrZero() == b.toAmountOrZero() },
                    onCommit = { text -> viewModel.update { it.copy(maxDistanceKm = text.toAmountOrZero()) } }
                )
            }
        }

        item {
            SectionCard("Kelimeler") {
                PersistedTextField(
                    label = "Kabul düğmesinin yazısı",
                    persisted = settings.acceptLabels.joinToString(", "),
                    supporting = "Virgülle ayırın. Uygulamanızdaki düğmede tam olarak ne yazıyorsa onu yazın.",
                    onCommit = { text ->
                        viewModel.update { it.copy(acceptLabels = text.splitCsv()) }
                    }
                )
                PersistedTextField(
                    label = "Yasaklı kelimeler",
                    persisted = settings.blockedKeywords.joinToString(", "),
                    supporting = "Bu kelimelerden biri geçerse çağrı atlanır. Örn: havalimanı, kurye",
                    onCommit = { text ->
                        viewModel.update { it.copy(blockedKeywords = text.splitCsv()) }
                    }
                )
                PersistedTextField(
                    label = "Zorunlu kelimeler",
                    persisted = settings.requiredKeywords.joinToString(", "),
                    supporting = "Doluysa, en az biri geçmeyen çağrı atlanır.",
                    onCommit = { text ->
                        viewModel.update { it.copy(requiredKeywords = text.splitCsv()) }
                    }
                )
            }
        }

        item {
            SectionCard("Sınırlar") {
                PersistedTextField(
                    label = "İki kabul arası bekleme (saniye)",
                    persisted = settings.cooldownSeconds.toFieldText(),
                    keyboardType = KeyboardType.Number,
                    matches = { a, b -> a.toIntOrZero() == b.toIntOrZero() },
                    onCommit = { text -> viewModel.update { it.copy(cooldownSeconds = text.toIntOrZero()) } }
                )
                PersistedTextField(
                    label = "Günlük kabul sınırı — boş = sınırsız",
                    persisted = settings.maxAcceptsPerDay.toFieldText(),
                    keyboardType = KeyboardType.Number,
                    matches = { a, b -> a.toIntOrZero() == b.toIntOrZero() },
                    onCommit = { text -> viewModel.update { it.copy(maxAcceptsPerDay = text.toIntOrZero()) } }
                )
                Text(
                    "Bugün otomatik kabul: ${counters.acceptsToday}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            SectionCard("Çalışma saatleri") {
                SwitchRow(
                    label = "Saat aralığı uygula",
                    description = "Gece yarısını aşan aralıklar desteklenir (örn. 22:00-06:00).",
                    checked = settings.workingHoursEnabled,
                    onCheckedChange = { on -> viewModel.update { it.copy(workingHoursEnabled = on) } }
                )
                if (settings.workingHoursEnabled) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(modifier = Modifier.weight(1f)) {
                            PersistedTextField(
                                label = "Başlangıç",
                                persisted = RuleEngine.hhmm(settings.workStartMinute),
                                matches = { a, b -> a.toMinuteOfDay() == b.toMinuteOfDay() },
                                onCommit = { text ->
                                    text.toMinuteOfDay()?.let { m ->
                                        viewModel.update { it.copy(workStartMinute = m) }
                                    }
                                }
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            PersistedTextField(
                                label = "Bitiş",
                                persisted = RuleEngine.hhmm(settings.workEndMinute),
                                matches = { a, b -> a.toMinuteOfDay() == b.toMinuteOfDay() },
                                onCommit = { text ->
                                    text.toMinuteOfDay()?.let { m ->
                                        viewModel.update { it.copy(workEndMinute = m) }
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionCard("Diğer") {
                SwitchRow(
                    label = "Bildirimlerden gelen çağrıları da işle",
                    checked = settings.handleNotifications,
                    onCheckedChange = { on -> viewModel.update { it.copy(handleNotifications = on) } }
                )
                SwitchRow(
                    label = "Kabul edince titret",
                    checked = settings.vibrateOnAccept,
                    onCheckedChange = { on -> viewModel.update { it.copy(vibrateOnAccept = on) } }
                )
                SwitchRow(
                    label = "Tanılama modu",
                    description = "Normalde sessiz geçilen her şeyi de kaydeder: ekranda hangi " +
                        "uygulama var, ekrandan ne okundu, pencere okunabildi mi. " +
                        "\"Hiçbir şey olmuyor\" durumunu çözmek için bunu açın.",
                    checked = settings.diagnosticMode,
                    onCheckedChange = { on -> viewModel.update { it.copy(diagnosticMode = on) } }
                )
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showAppPicker) {
        AppPickerDialog(
            viewModel = viewModel,
            selected = settings.targetPackages,
            onToggle = viewModel::toggleTarget,
            onDismiss = { showAppPicker = false }
        )
    }
}

/**
 * Ana kontrol. Tek anahtar [com.taksi.autoaccept.core.model.FilterSettings.enabled];
 * on plan servisi yalnizca bunun gorunur karsiligi.
 */
@Composable
private fun RunControlCard(
    running: Boolean,
    dryRun: Boolean,
    blockReason: String?,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (running) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                if (running) "ÇALIŞIYOR" else "DURDURULDU",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                when {
                    running && dryRun ->
                        "Çağrılar arka planda izleniyor ama deneme modu açık: düğmeye basılmıyor, " +
                            "sadece kayıt tutuluyor."

                    running -> "Çağrılar arka planda izleniyor ve kuralınıza uyanlar kabul ediliyor."
                    else -> "Hiçbir çağrı işlenmiyor."
                },
                style = MaterialTheme.typography.bodyMedium
            )

            if (blockReason != null && !running) {
                Spacer(Modifier.height(8.dp))
                Text(
                    blockReason,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = if (running) onStop else onStart,
                enabled = running || blockReason == null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                colors = if (running) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                Text(
                    if (running) "DURDUR" else "BAŞLAT",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    serviceEnabled: Boolean,
    serviceRunning: Boolean,
    onOpenSettings: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (serviceEnabled) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                if (serviceEnabled) "Erişilebilirlik servisi açık" else "Erişilebilirlik servisi kapalı",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (serviceEnabled) {
                    "Uygulama ekrandaki çağrıları okuyabiliyor."
                } else {
                    "Bu uygulamanın çağrıları görebilmesi için Ayarlar → Erişilebilirlik → " +
                        "Taksi Oto Kabul servisini açmanız gerekiyor."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (serviceEnabled && !serviceRunning) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Ayarlarda açık görünüyor ama servis çalışmıyor. Servisi kapatıp " +
                        "tekrar açın; sorun sürerse telefonu yeniden başlatın.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onOpenSettings) { Text("Erişilebilirlik ayarlarını aç") }
        }
    }
}

@Composable
private fun AppPickerDialog(
    viewModel: MainViewModel,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val apps by viewModel.apps.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadApps() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("İzlenecek uygulamalar") },
        text = {
            if (apps.isEmpty()) {
                Text("Uygulamalar yükleniyor…")
            } else {
                LazyColumn(modifier = Modifier.height(420.dp)) {
                    items(apps, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = app.packageName in selected,
                                onCheckedChange = { onToggle(app.packageName) }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    app.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tamam") } }
    )
}

private fun String.splitCsv(): List<String> =
    split(",").map { it.trim() }.filter { it.isNotEmpty() }

/** "08:30" -> 510. Hatali girdide null doner, ayar degismez. */
private fun String.toMinuteOfDay(): Int? {
    val parts = trim().split(":")
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return hour * 60 + minute
}
