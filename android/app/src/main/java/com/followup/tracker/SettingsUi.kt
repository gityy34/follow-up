package com.followup.tracker

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.AtomicFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.*

@Composable
internal fun SettingsPanel(workspace: Workspace, model: JourneyModel, deviceSecure: () -> Boolean, demo: Boolean,
                           demoMode: (Boolean) -> Unit, snack: SnackbarHostState) {
    val config = workspace.settings
    val repo = model.repo
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var backupDialog by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<Workspace?>(null) }
    var feedback by remember { mutableStateOf("") }
    var appearance by remember { mutableStateOf(false) }
    var confirmAction by remember { mutableStateOf("") }
    var canNotify by remember { mutableStateOf(model.reminders.canNotify()) }
    var canExact by remember { mutableStateOf(model.reminders.canUseExact()) }
    val safetyFile = remember { File(context.filesDir, "before-restore.youbackup") }
    var hasSafetyCopy by remember { mutableStateOf(safetyFile.exists()) }

    fun runJob(work: suspend () -> String) {
        if (busy) return
        busy = true
        scope.launch {
            val result = try { work() } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { "Operation failed. Check the file, passphrase and available storage." }
                finally { busy = false }
            feedback = result
        }
    }

    fun change(value: AppSettings) {
        scope.launch {
            try { repo.settings.set(value); model.reminders.request() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { snack.showSnackbar("Settings could not be saved.") }
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        canNotify = model.reminders.canNotify()
        if (!it) feedback = "Android notifications are off. You can enable them in system settings."
        change(config.copy(notifications = it))
    }
    val systemSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        canNotify = model.reminders.canNotify()
        canExact = model.reminders.canUseExact()
        model.reminders.request()
    }
    val exportBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val secret = password.toCharArray()
        password = ""
        confirmation = ""
        if (uri != null && secret.size >= 10) {
            runJob {
                try {
                    val snapshot = repo.snapshot().let { it.copy(settings = it.settings.copy(lastExport = now(), exportLabel = "External document")) }
                    withContext(Dispatchers.IO) {
                        val data = BackupCodec.export(snapshot, secret)
                        context.contentResolver.openOutputStream(uri, "wt").use { out -> requireNotNull(out).write(data) }
                        context.contentResolver.openInputStream(uri).use { input ->
                            check(BackupCodec.import(requireNotNull(input), secret) == snapshot.copy(
                                followUps = snapshot.followUps.map { it.copy(reminderScheduledAt = null, reminderStatus = "pending") }))
                        }
                    }
                    repo.settings.set(snapshot.settings)
                    "Backup saved and verified · ${snapshot.customers.size} customers, ${snapshot.followUps.size} follow-ups, ${snapshot.calls.size} calls."
                } finally { secret.fill('\u0000') }
            }
        } else {
            secret.fill('\u0000')
            if (uri != null) feedback = "Backup passphrase expired. Start the export again."
        }
    }
    val exportSafetyCopy = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) runJob {
            withContext(Dispatchers.IO) {
                AtomicFile(safetyFile).openRead().use { input ->
                    context.contentResolver.openOutputStream(uri, "wt").use { output -> input.copyTo(requireNotNull(output)) }
                }
            }
            "Encrypted pre-restore copy saved. Use the passphrase entered for the import to open it."
        }
    }
    val selectBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { importUri = uri; password = ""; backupDialog = "Import Backup" }
    }

    if (appearance) {
        Panel {
            Section("Appearance")
            Choice("Theme", ThemeMode.entries, config.theme, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { change(config.copy(theme = it)) }
            Choice("Palette", listOf("Violet", "Ocean", "Forest"), config.palette, { it }) { change(config.copy(palette = it)) }
            Text("Custom layout presets · Coming Soon")
        }
        Primary("Back to Settings") { appearance = false }
        return
    }

    Panel {
        Section("Appearance")
        OutlinedButton({ appearance = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open Appearance") }
        Choice("Deal currency", (listOf("PKR", "USD", "EUR", "GBP", "AED", "SAR") + currencyCodes + config.currency).distinct(),
            config.currency, { it }) { change(config.copy(currency = it)) }
        Text("The currency applies to new deals. Existing deals keep the currency recorded when they were saved.")
        Choice("Daily call goal", (listOf(1, 3, 5, 10, 15, 20, 30, 50) + config.goal).distinct().sorted(),
            config.goal, { it.toString() }) { change(config.copy(goal = it)) }
    }
    Panel {
        Section("Notifications")
        Text("Enable reminders to get a notification before an upcoming follow-up. The default is one day before at the same local time.")
        PreferenceSwitch("Follow-up reminders", config.notifications && canNotify) { on ->
            if (on && !canNotify && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else if (on && !canNotify) systemSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            else change(config.copy(notifications = on))
        }
        if (!canNotify) TextButton({ systemSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) {
            Text("Open Android notification settings")
        }
        Choice("Default reminder", Reminder.entries.filter { it != Reminder.DEFAULT && it != Reminder.CUSTOM },
            config.defaultReminder, { it.title }) { change(config.copy(defaultReminder = it)) }
        Text(if (canExact) "Exact alarms are available for time-critical reminders."
        else "Android may delay best-effort reminders. Exact alarm access is optional.")
        if (!canExact && Build.VERSION.SDK_INT >= 31) TextButton({
            try { systemSettings.launch(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}"))) }
            catch (_: Exception) { feedback = "Open Android settings to manage exact alarm access." }
        }) { Text("Manage exact alarm access") }
    }
    Panel {
        Section("Privacy")
        PreferenceSwitch("App lock", config.appLock) { on ->
            if (on && !deviceSecure()) feedback = "Set a device PIN, password, or pattern in Android settings first."
            else change(config.copy(appLock = on))
        }
        Choice("Lock after", (listOf(0, 1, 5, 15, 30, 60) + config.lockAfter).distinct().sorted(), config.lockAfter,
            { if (it == 0) "Immediately" else "$it min" }) { change(config.copy(lockAfter = it)) }
        PreferenceSwitch("Hide previews in recent apps", config.privateRecents) { change(config.copy(privateRecents = it)) }
    }
    Panel {
        Section("Encrypted Backup")
        Text("Export to Documents or another location you control before uninstalling or changing phones. Uninstalling removes the app database.")
        Text(if (config.lastExport > 0) "Last verified export · ${dateLabel(config.lastExport)} · ${config.exportLabel}" else "No verified external backup yet.")
        Primary("Export Backup", !busy) { password = ""; confirmation = ""; backupDialog = "Export Backup" }
        OutlinedButton({ selectBackup.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Import and Verify Backup")
        }
        if (hasSafetyCopy) TextButton({ exportSafetyCopy.launch("YoU-Follow-ups-before-restore.youbackup") }, enabled = !busy) {
            Text("Export Pre-Restore Copy")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    if (feedback.isNotBlank()) Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Text(feedback, Modifier.padding(12.dp))
    }
    Panel {
        Section("Data Retention")
        Text("Archived customers and call history stay on this device. Use this control to permanently clear older deleted follow-ups.")
        Choice("Deleted follow-up retention", (listOf(30, 90, 180, 365) + config.retentionDays).distinct().sorted(),
            config.retentionDays, { "$it days" }) { change(config.copy(retentionDays = it)) }
        TextButton({ confirmAction = "Purge older deleted follow-ups" }, enabled = !busy) { Text("Purge older deleted follow-ups") }
    }
    Panel {
        Section("Demo Workspace")
        Text("Sample records stay separate from customer records and never trigger reminders.")
        if (workspace.customers.any { it.demo }) {
            PreferenceSwitch("View demo workspace", demo, demoMode)
            TextButton({ confirmAction = "Remove demo data" }, enabled = !busy) { Text("Remove demo data") }
        } else TextButton({ runJob { repo.seedDemo(); demoMode(true); "Demo workspace added." } }, enabled = !busy) { Text("Load demo workspace") }
    }
    Panel { Section("Sign in & Sync"); Text("Coming Soon"); Text("Your work is stored on this device.") }

    if (backupDialog.isNotEmpty()) {
        val exporting = backupDialog == "Export Backup"
        fun close() { backupDialog = ""; password = ""; confirmation = ""; importUri = null; preview = null }
        val verified = preview
        AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text(backupDialog) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (verified != null) {
                    Text("Verified ${verified.customers.size} customers, ${verified.followUps.size} follow-ups and ${verified.calls.size} call records.")
                    Text("Restore will replace current records and settings after making an encrypted safety backup.")
                } else {
                    Text(if (exporting) "Choose a passphrase with at least 10 characters. You will need it to restore." else "Enter the passphrase to verify this backup before restoring.")
                    OutlinedTextField(password, { password = it.take(512) }, label = { Text("Passphrase") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation())
                    if (exporting) OutlinedTextField(confirmation, { confirmation = it.take(512) }, label = { Text("Confirm passphrase") },
                        singleLine = true, visualTransformation = PasswordVisualTransformation())
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }, confirmButton = {
            TextButton(enabled = !busy && password.length >= 10 && (!exporting || password == confirmation), onClick = {
                when {
                    exporting -> { backupDialog = ""; exportBackup.launch("YoU-Follow-ups-${java.time.LocalDate.now()}.youbackup") }
                    verified != null -> {
                        val secret = password.toCharArray()
                        close()
                        runJob {
                            try {
                                val before = repo.snapshot()
                                withContext(Dispatchers.IO) {
                                    val safety = AtomicFile(safetyFile)
                                    val data = BackupCodec.export(before, secret)
                                    val stream = safety.startWrite()
                                    try { stream.write(data); safety.finishWrite(stream) }
                                    catch (error: Exception) { safety.failWrite(stream); throw error }
                                }
                                hasSafetyCopy = true
                                repo.replace(verified.copy(settings = verified.settings.copy(appLock = verified.settings.appLock && deviceSecure())))
                                repo.applyRestoreSettings()
                                model.reminders.request()
                                "Restored ${verified.customers.size} customers, ${verified.followUps.size} follow-ups and ${verified.calls.size} calls."
                            } finally { secret.fill('\u0000') }
                        }
                    }
                    else -> {
                        val secret = password.toCharArray()
                        val uri = importUri
                        runJob {
                            try {
                                preview = withContext(Dispatchers.IO) {
                                    context.contentResolver.openInputStream(requireNotNull(uri)).use { input -> BackupCodec.import(requireNotNull(input), secret) }
                                }
                                "Backup verified. Confirm restore to replace current data."
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) {
                                close()
                                "Backup verification failed. Check the passphrase or choose a valid backup. No records were changed."
                            } finally { secret.fill('\u0000') }
                        }
                    }
                }
            }) { Text(if (exporting) "Choose Location" else if (verified != null) "Replace Data" else "Verify") }
        }, dismissButton = { TextButton(enabled = !busy, onClick = { close() }) { Text("Cancel") } })
    }

    if (confirmAction.isNotBlank()) {
        AlertDialog(onDismissRequest = { confirmAction = "" }, title = { Text(confirmAction) },
            text = { Text("This action permanently removes the selected records.") },
            confirmButton = { TextButton({
                val removeDemo = confirmAction == "Remove demo data"
                confirmAction = ""
                runJob {
                    if (removeDemo) { repo.removeDemo(); demoMode(false); "Demo data removed." }
                    else "${repo.purgeDeleted(config.retentionDays)} older deleted follow-ups removed."
                }
            }) { Text("Confirm") } }, dismissButton = { TextButton({ confirmAction = "" }) { Text("Cancel") } })
    }
}
