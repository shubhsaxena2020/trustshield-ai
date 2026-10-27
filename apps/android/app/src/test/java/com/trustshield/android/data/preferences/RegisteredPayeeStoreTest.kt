package com.trustshield.android.data.preferences

import ai.trustshield.android.data.preferences.PayeeKeyValueStore
import ai.trustshield.android.data.preferences.RegisteredPayeeStore
import ai.trustshield.android.data.preferences.RegisteredPayeeStoreKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adversarial audit of payee canonicalisation, duplicate handling, recovery and persisted privacy. */
class RegisteredPayeeStoreAuditTest {
    private val salt = ByteArray(16) { (it + 1).toByte() }

    @Test
    fun normalizedDuplicateAcrossInstancesDoesNotAddAnEntryOrRewriteStorage() {
        val backend = MemoryPayeeStore()
        RegisteredPayeeStore(backend, salt).register("  Shop@OkAxis  ")
        val writesAfterFirstRegistration = backend.writes

        val reloaded = RegisteredPayeeStore(backend, salt)
        reloaded.register("SHOP@OKAXIS")

        assertEquals(1, reloaded.count())
        assertTrue(reloaded.matches(" shop@okaxis "))
        assertEquals(writesAfterFirstRegistration, backend.writes)
        assertEquals(listOf("sho***@okaxis"), reloaded.maskedList())
    }

    @Test
    fun corruptOrMisalignedStoredValuesNeverExposeContentOrCrashReads() {
        val backend = MemoryPayeeStore()
        backend.values[RegisteredPayeeStoreKeys.IDS] = "bad-digest,,${"A".repeat(64)}"
        backend.values[RegisteredPayeeStoreKeys.MASKED] = "attacker@bank,raw@merchant"

        val store = RegisteredPayeeStore(backend, salt)

        assertTrue(store.isEmpty())
        assertEquals(0, store.count())
        assertEquals(emptyList<String>(), store.maskedList())
        assertFalse(store.matches("raw@merchant"))
    }

    @Test
    fun truncatedDisplayDataFallsBackToMaskWithoutLosingValidMatch() {
        val backend = MemoryPayeeStore()
        val writer = RegisteredPayeeStore(backend, salt)
        writer.register("shop@okaxis")
        backend.values.remove(RegisteredPayeeStoreKeys.MASKED)

        val recovered = RegisteredPayeeStore(backend, salt)
        assertTrue(recovered.matches("SHOP@OKAXIS"))
        assertEquals(listOf(RegisteredPayeeStore.UNKNOWN_MASKED), recovered.maskedList())
        assertFalse(recovered.maskedList().any { it.contains("shop@okaxis") })
    }

    @Test
    fun persistedContentAndRejectedInputExceptionDoNotLeakRawUpi() {
        val backend = MemoryPayeeStore()
        val store = RegisteredPayeeStore(backend, salt)
        val secretVpa = "private-person@privatebank"
        store.register(secretVpa)

        assertTrue(backend.values.values.none { it.contains(secretVpa) || it.contains("private-person") })
        assertEquals(listOf("pri***@privatebank"), store.maskedList())

        val invalid = "private-person-not-a-vpa"
        try {
            store.register(invalid)
            throw AssertionError("malformed value unexpectedly accepted")
        } catch (expected: IllegalArgumentException) {
            assertFalse(expected.message.orEmpty().contains(invalid))
            assertFalse(expected.message.orEmpty().contains("private-person"))
        }
        assertTrue(backend.values.values.none { it.contains(invalid) })
    }

    private class MemoryPayeeStore : PayeeKeyValueStore {
        val values = linkedMapOf<String, String>()
        var writes = 0
            private set

        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
            writes++
        }
        override fun remove(key: String) {
            values.remove(key)
        }
    }
}
