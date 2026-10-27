package ai.trustshield.android.core.payment

import ai.trustshield.android.data.preferences.PayeeKeyValueStore
import ai.trustshield.android.data.preferences.RegisteredPayeeStore
import ai.trustshield.android.data.preferences.RegisteredPayeeStoreKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RegisteredPayeeStoreTest {
    private val salt = ByteArray(16) { (it + 1).toByte() }

    @Test
    fun `raw identifier is not persisted and a fresh instance restores match`() {
        val backend = MemoryStore()
        RegisteredPayeeStore(backend, salt).register("private-shop@okaxis")

        val persisted = backend.values.values.joinToString("|")
        assertFalse(persisted.contains("private-shop@okaxis"))
        assertFalse(persisted.contains("private-shop"))
        assertTrue(RegisteredPayeeStore(backend, salt).matches("PRIVATE-SHOP@OKAXIS"))
    }

    @Test
    fun `invalid or blank decoded identifiers do not match or throw`() {
        val store = RegisteredPayeeStore(MemoryStore(), salt)

        assertFalse(store.matches(""))
        assertFalse(store.matches("not-a-vpa"))
        assertFalse(store.matches("@bank"))
        assertFalse(store.matches("name@"))
    }

    @Test
    fun `registration validates input without leaking it through exception text`() {
        val privateId = "private-shop@okaxis"
        val store = RegisteredPayeeStore(MemoryStore(), salt)
        val error = runCatching { store.register("$privateId extra") }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertFalse(error!!.message.orEmpty().contains(privateId))
        assertTrue(store.isEmpty())
    }

    @Test
    fun `registration is normalized idempotent and preserves ordered masked entries after restart`() {
        val backend = MemoryStore()
        val store = RegisteredPayeeStore(backend, salt)

        store.register("  Shop@OkAxis  ")
        store.register("shop@okaxis")
        store.register("market@ybl")

        val restored = RegisteredPayeeStore(backend, salt)
        assertEquals(2, restored.count())
        assertEquals(listOf("sho***@okaxis", "mar***@ybl"), restored.maskedList())
        assertTrue(restored.matches("SHOP@OKAXIS"))
        assertTrue(restored.matches("market@ybl"))
    }

    @Test
    fun `corrupt and incomplete persisted values are ignored or safely masked`() {
        val backend = MemoryStore()
        backend.values[RegisteredPayeeStoreKeys.IDS] = "bad-id,${"a".repeat(64)},${"b".repeat(64)}"
        backend.values[RegisteredPayeeStoreKeys.MASKED] = "raw@bank,shop@bank,attacker text"

        val restored = RegisteredPayeeStore(backend, salt)
        assertEquals(2, restored.count())
        assertEquals(listOf("***", "***"), restored.maskedList())
        assertFalse(restored.matches("anything@bank"))
    }

    @Test
    fun `failed write propagates and retry sees no phantom registration`() {
        val backend = MemoryStore(failOnPutNumber = 1)
        val store = RegisteredPayeeStore(backend, salt)
        val error = runCatching { store.register("shop@okaxis") }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertFalse(store.matches("shop@okaxis"))
        assertEquals(0, store.count())

        backend.failOnPutNumber = null
        store.register("shop@okaxis")
        assertTrue(RegisteredPayeeStore(backend, salt).matches("shop@okaxis"))
    }

    @Test
    fun `unknown removal and clearing are safe and clear persisted keys`() {
        val backend = MemoryStore()
        val store = RegisteredPayeeStore(backend, salt)
        store.register("shop@okaxis")

        store.remove("unknown@ybl")
        assertTrue(store.matches("shop@okaxis"))
        store.clear()

        assertFalse(store.matches("shop@okaxis"))
        assertFalse(backend.values.containsKey(RegisteredPayeeStoreKeys.IDS))
        assertFalse(backend.values.containsKey(RegisteredPayeeStoreKeys.MASKED))
    }

    private class MemoryStore(
        var failOnPutNumber: Int? = null
    ) : PayeeKeyValueStore {
        val values = mutableMapOf<String, String>()
        private var putCount = 0

        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            putCount++
            if (putCount == failOnPutNumber) error("simulated write failure")
            values[key] = value
        }
        override fun remove(key: String) { values.remove(key) }
    }
}
