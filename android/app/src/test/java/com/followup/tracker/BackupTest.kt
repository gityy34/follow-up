package com.followup.tracker

import org.junit.Assert.*
import org.junit.Test

class BackupTest {
    private val pass = "long test passphrase".toCharArray()
    private val customer = Customer(displayName = "Example", phoneNumber = "+1 555 1234")
    private val state = Workspace(customers = listOf(customer), settings = AppSettings(theme = ThemeMode.DARK))

    @Test fun encryptedBackupRoundTripsAndRejectsTampering() {
        val file = BackupCodec.export(state, pass)
        assertFalse(file.toString(Charsets.ISO_8859_1).contains(customer.phoneNumber))
        assertEquals(state, BackupCodec.import(file.inputStream(), pass))
        assertThrows(Exception::class.java) { BackupCodec.import(file.inputStream(), "wrong passphrase".toCharArray()) }
        assertThrows(Exception::class.java) { BackupCodec.import(file.copyOf(34).inputStream(), pass) }
        file[file.lastIndex] = (file.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { BackupCodec.import(file.inputStream(), pass) }
    }

    @Test fun rejectsDuplicateIdsAndDanglingRelationships() {
        val duplicate = state.copy(customers = listOf(customer, customer))
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.import(BackupCodec.export(duplicate, pass).inputStream(), pass)
        }
        val invalid = state.copy(followUps = listOf(FollowUp(customerId = uuid(), purpose = "Invalid", dueAt = now())))
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.import(BackupCodec.export(invalid, pass).inputStream(), pass)
        }
    }
    @Test fun backupPreservesAmountsCurrencyAndPendingCalls() {
        val savedAt = now()
        val pending = CallLog(customerId = customer.id, startedAt = savedAt - 100, endedAt = savedAt - 10, outcome = Outcome.PENDING)
        val resolved = pending.copy(id = uuid(), outcome = Outcome.RESOLVED, dealAmount = "9999999999999999999999.123456789",
            dealCurrency = "PKR", outcomeAt = savedAt)
        val workspace = state.copy(calls = listOf(pending, resolved), settings = state.settings.copy(currency = "USD", operatorName = "Uzair", displayName = "My work", profileConfigured = true))
        assertEquals(workspace, BackupCodec.import(BackupCodec.export(workspace, pass).inputStream(), pass))
    }
    @Test fun profileReviewFontAndThumbnailRoundTrip() {
        val profile = AppSettings(operatorName = "Uzair", displayName = "Callx", profileReview = PROFILE_REVIEW,
            profileConfigured = true, photo = "/9j/AA==", font = AppFont.MODERN)
        assertEquals(profile, settingsFromJson(settingsJson(profile)))
        val restored = BackupCodec.import(BackupCodec.export(Workspace(settings = profile), pass).inputStream(), pass)
        assertEquals(profile, restored.settings)
        val existing = settingsFromJson(org.json.JSONObject("""{"operatorName":"Uzair","profileConfigured":true}"""))
        assertEquals("Uzair", existing.operatorName)
        assertEquals(0, existing.profileReview)
        assertEquals(AppFont.DEFAULT, existing.font)
        assertFalse(isProfilePhoto("/9j/<svg>"))
        assertFalse(isProfilePhoto("/9j/" + "A".repeat(90000)))
    }
}
