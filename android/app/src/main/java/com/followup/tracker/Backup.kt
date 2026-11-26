package com.followup.tracker

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.EOFException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.ZoneId
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

internal fun obj(vararg values: Pair<String, Any?>) = JSONObject().apply {
    values.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
}

internal inline fun <reified E : Enum<E>> JSONObject.enum(key: String, fallback: E): E =
    optString(key).takeIf(String::isNotBlank)?.let { enumValueOf<E>(it) } ?: fallback
private fun JSONObject.longOrNull(key: String) = if (isNull(key)) null else getLong(key)
private fun JSONObject.stringOrNull(key: String) = if (isNull(key)) null else getString(key)

internal fun settingsJson(s: AppSettings) = obj(
    "themeMode" to s.theme.name, "selectedPalette" to s.palette, "notificationEnabled" to s.notifications,
    "defaultReminderOffset" to s.defaultReminder.name, "appLockEnabled" to s.appLock, "lockAfter" to s.lockAfter,
    "privateRecents" to s.privateRecents, "goal" to s.goal, "retentionDays" to s.retentionDays,
    "lastExport" to s.lastExport, "exportLabel" to s.exportLabel, "onboardingDone" to s.onboardingDone, "currency" to s.currency,
    "operatorName" to s.operatorName, "displayName" to s.displayName, "profileConfigured" to s.profileConfigured,
    "profileReview" to s.profileReview, "photo" to s.photo, "photoSource" to s.photoSource, "font" to s.font.name,
    "paymentPeriod" to s.paymentPeriod.name
)

internal fun settingsFromJson(s: JSONObject) = AppSettings(
    s.enum("themeMode", ThemeMode.NEON).let { if (it == ThemeMode.SYSTEM && !s.has("profileConfigured")) ThemeMode.NEON else it }, s.optString("selectedPalette", "Violet").takeIf { it in listOf("Violet", "Ocean", "Forest") } ?: "Violet",
    s.optBoolean("notificationEnabled"), s.enum("defaultReminderOffset", Reminder.DAY).takeUnless { it == Reminder.CUSTOM || it == Reminder.DEFAULT } ?: Reminder.DAY,
    s.optBoolean("appLockEnabled"), s.optInt("lockAfter", 1).coerceIn(0, 60), s.optBoolean("privateRecents"),
    s.optInt("goal", 5).coerceIn(1, 500), s.optInt("retentionDays", 90).coerceIn(1, 3650),
    s.optLong("lastExport"), s.optString("exportLabel").take(80), s.optBoolean("onboardingDone"),
    s.optString("currency").takeIf(::validCurrency) ?: "PKR",
    s.optString("operatorName").trim().take(60), s.optString("displayName").trim().take(60), s.optBoolean("profileConfigured"),
    s.optInt("profileReview").coerceAtLeast(0), s.optString("photo").takeIf { isProfilePhoto(it) }.orEmpty(),
    s.optString("photoSource").takeIf { isProfilePhoto(it, 350_000) }.orEmpty(),
    AppFont.entries.firstOrNull { it.name == s.optString("font") } ?: AppFont.DEFAULT,
    PaymentPeriod.entries.firstOrNull { it.name == s.optString("paymentPeriod") } ?: PaymentPeriod.DAILY
)

internal data class BackupManifest(val formatVersion: Int, val createdAt: Long, val checksum: String,
    val encrypted: Boolean, val appVersion: String)

internal object BackupCodec {
    private val magic = "YOUFUP1".toByteArray()
    private const val saltSize = 16
    private const val nonceSize = 12
    private const val maxSize = 100 * 1024 * 1024

    private fun cipher(mode: Int, passphrase: CharArray, salt: ByteArray, nonce: ByteArray): Cipher {
        val spec = PBEKeySpec(passphrase, salt, 600_000, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
        return try { Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(magic)
        } } finally { key.fill(0) }
    }

    fun export(workspace: Workspace, passphrase: CharArray): ByteArray {
        require(passphrase.size >= 10) { "Use a passphrase of at least 10 characters." }
        val random = SecureRandom()
        val salt = ByteArray(saltSize).also(random::nextBytes)
        val nonce = ByteArray(nonceSize).also(random::nextBytes)
        val data = encode(workspace).toString()
        val manifest = obj("formatVersion" to 2, "createdAt" to now(), "checksum" to digest(data.toByteArray()),
            "encrypted" to true, "appVersion" to "1.6.0")
        val payload = obj("manifest" to manifest, "data" to data).toString().toByteArray()
        require(payload.size <= maxSize) { "This backup exceeds this version’s file size limit." }
        return try { magic + salt + nonce + cipher(Cipher.ENCRYPT_MODE, passphrase, salt, nonce).doFinal(payload) }
        finally { payload.fill(0) }
    }

    fun import(input: InputStream, passphrase: CharArray): Workspace {
        val header = ByteArray(magic.size + saltSize + nonceSize)
        var read = 0
        while (read < header.size) {
            val count = input.read(header, read, header.size - read)
            if (count < 0) throw EOFException("Backup is incomplete.")
            read += count
        }
        require(header.copyOf(magic.size).contentEquals(magic)) { "This is not a YoU Follow ups backup." }
        val salt = header.copyOfRange(magic.size, magic.size + saltSize)
        val nonce = header.copyOfRange(magic.size + saltSize, header.size)
        val chunks = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val len = input.read(chunk)
            if (len < 0) break
            require(chunks.size() + len <= maxSize + 16) { "Backup file is too large." }
            chunks.write(chunk, 0, len)
        }
        val bytes = cipher(Cipher.DECRYPT_MODE, passphrase, salt, nonce).doFinal(chunks.toByteArray())
        return try {
            val packet = JSONObject(bytes.toString(Charsets.UTF_8))
            val raw = packet.getJSONObject("manifest")
            val manifest = BackupManifest(raw.getInt("formatVersion"), raw.getLong("createdAt"),
                raw.getString("checksum"), raw.getBoolean("encrypted"), raw.getString("appVersion"))
            require(manifest.formatVersion in 1..2 && manifest.encrypted && manifest.createdAt > 0 && manifest.appVersion.isNotBlank()) {
                "Unsupported backup version."
            }
            val data = packet.getString("data")
            require(MessageDigest.isEqual(digest(data.toByteArray()).toByteArray(), manifest.checksum.toByteArray())) { "Backup checksum mismatch." }
            decode(JSONObject(data))
        } finally { bytes.fill(0) }
    }

    private fun digest(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun <T> JSONArray.items(convert: (JSONObject) -> T): List<T> = List(length()) { convert(getJSONObject(it)) }
    private fun id(value: String) = UUID.fromString(value).also { require(it.toString() == value) }.toString()

    private fun encode(w: Workspace) = obj(
        "settings" to settingsJson(w.settings),
        "customers" to JSONArray(w.customers.map { c -> obj("id" to c.id, "displayName" to c.displayName, "phoneNumber" to c.phoneNumber,
            "email" to c.email, "company" to c.company, "tags" to c.tags, "priority" to c.priority.name, "stage" to c.stage.name,
            "notes" to c.notes, "archived" to c.archived, "demo" to c.demo, "createdAt" to c.createdAt, "updatedAt" to c.updatedAt) }),
        "followUps" to JSONArray(w.followUps.map { f -> obj("id" to f.id, "customerId" to f.customerId, "purpose" to f.purpose,
            "dueAt" to f.dueAt, "timezoneId" to f.timezoneId, "reminderPolicy" to f.reminderPolicy.name, "customReminderAt" to f.customReminderAt,
            "timeCritical" to f.timeCritical, "alertedAt" to f.alertedAt, "overdueNotifiedAt" to f.overdueNotifiedAt,
            "snoozedUntil" to f.snoozedUntil, "status" to f.status.name, "priority" to f.priority.name, "note" to f.note,
            "createdAt" to f.createdAt, "updatedAt" to f.updatedAt) }),
        "calls" to JSONArray(w.calls.map { c -> obj("id" to c.id, "customerId" to c.customerId, "startedAt" to c.startedAt,
            "endedAt" to c.endedAt, "outcome" to c.outcome.name, "issueCategory" to c.issueCategory, "summaryNote" to c.summaryNote,
            "reflection" to c.reflection, "followUpId" to c.followUpId, "createdAt" to c.createdAt,
            "dealAmount" to c.dealAmount, "dealCurrency" to c.dealCurrency, "outcomeAt" to c.outcomeAt) }),
        "timeline" to JSONArray(w.timeline.map { e -> obj("id" to e.id, "customerId" to e.customerId, "eventType" to e.eventType,
            "referenceId" to e.referenceId, "eventTime" to e.eventTime, "displayPayload" to e.displayPayload) }),
        "payments" to JSONArray(w.payments.map { row -> obj("id" to row.id, "date" to row.date, "amount" to row.amount,
            "currency" to row.currency, "createdAt" to row.createdAt, "updatedAt" to row.updatedAt) })
    )

    private fun decode(data: JSONObject): Workspace {
        val customers = data.getJSONArray("customers").items { c ->
            Customer(id(c.getString("id")), c.getString("displayName"), c.optString("phoneNumber"), c.optString("email"),
                c.optString("company"), c.optString("tags"), c.enum("priority", Priority.NORMAL), c.enum("stage", Stage.NEW),
                c.optString("notes"), c.optBoolean("archived"), c.optBoolean("demo"), c.getLong("createdAt"), c.getLong("updatedAt"))
                .also { require(validCustomer(it.displayName, it.phoneNumber)) }
        }
        val customerIds = customers.mapTo(hashSetOf()) { it.id }
        require(customers.size == customerIds.size) { "Duplicate customer IDs." }
        val follows = data.getJSONArray("followUps").items { f ->
            FollowUp(id(f.getString("id")), id(f.getString("customerId")), f.getString("purpose"), f.getLong("dueAt"),
                ZoneId.of(f.getString("timezoneId")).id, f.enum("reminderPolicy", Reminder.DEFAULT), f.longOrNull("customReminderAt"),
                f.optBoolean("timeCritical"), alertedAt = f.longOrNull("alertedAt"), overdueNotifiedAt = f.longOrNull("overdueNotifiedAt"),
                snoozedUntil = f.longOrNull("snoozedUntil"), status = f.enum("status", FollowStatus.OPEN), priority = f.enum("priority", Priority.NORMAL),
                note = f.optString("note"), createdAt = f.getLong("createdAt"), updatedAt = f.getLong("updatedAt")).also {
                require(it.customerId in customerIds && it.purpose.trim().length in 1..120 && it.dueAt > 0)
                require(it.reminderPolicy != Reminder.CUSTOM || reminderTime(it, Reminder.DAY) != null)
            }
        }
        val followById = follows.associateBy { it.id }
        require(followById.size == follows.size)
        val calls = data.getJSONArray("calls").items { c ->
            CallLog(id(c.getString("id")), id(c.getString("customerId")), c.getLong("startedAt"), c.getLong("endedAt"),
                Outcome.valueOf(c.getString("outcome")), c.optString("issueCategory"), c.optString("summaryNote"),
                c.optString("reflection"), c.stringOrNull("followUpId"), c.getLong("createdAt"),
                c.stringOrNull("dealAmount"), c.stringOrNull("dealCurrency"), c.longOrNull("outcomeAt")).also {
                require(it.customerId in customerIds && it.startedAt in 1..it.endedAt && it.summaryNote.length <= 200 && it.reflection.length <= 200)
                require(it.followUpId == null || followById[it.followUpId]?.customerId == it.customerId)
                require(it.outcomeAt == null || it.outcomeAt >= it.endedAt)
                require(if (it.dealAmount == null) it.dealCurrency == null else
                    it.outcome == Outcome.RESOLVED && normalizeAmount(it.dealAmount) == it.dealAmount &&
                        it.dealCurrency?.let(::validCurrency) == true)
            }
        }
        val events = data.getJSONArray("timeline").items { e ->
            TimelineEvent(id(e.getString("id")), id(e.getString("customerId")), e.getString("eventType"),
                e.optString("referenceId"), e.getLong("eventTime"), e.getString("displayPayload"))
                .also { require(it.customerId in customerIds && it.eventType.isNotBlank()) }
        }
        require(calls.map { it.id }.toSet().size == calls.size && events.map { it.id }.toSet().size == events.size)
        val payments = if (!data.has("payments")) emptyList() else data.getJSONArray("payments").items { row ->
            PaymentEntry(id(row.getString("id")), row.getString("date"), row.getString("amount"), row.getString("currency"),
                row.getLong("createdAt"), row.getLong("updatedAt")).also {
                require(paymentDate(it.date) != null && validCurrency(it.currency) && normalizeAmount(it.amount) == it.amount &&
                    it.createdAt > 0 && it.updatedAt >= it.createdAt) { "Invalid payment entry." }
            }
        }
        require(payments.mapTo(hashSetOf()) { it.id }.size == payments.size) { "Duplicate payment IDs." }
        return Workspace(customers, follows, calls, events, settingsFromJson(data.getJSONObject("settings")), payments)
    }
}
