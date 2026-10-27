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
    fun `salt shorter than minimum is rejected and caller mutation cannot change keys`() {
        val backend = MemoryStore()
        val tooShort = ByteArray(15)
        assertTrue(runCatching { RegisteredPayeeStore(backend, tooShort) }
            .exceptionOrNull() is IllegalArgumentException)

        val callerSalt = salt.copyOf()
        val store = RegisteredPayeeStore(backend, callerSalt)
        store.register("shop@okaxis")
        callerSalt.fill(0)

        assertTrue(store.matches("SHOP@OKAXIS"))
        assertFalse(RegisteredPayeeStore(backend, callerSalt).matches("shop@okaxis"))
    }

    @Test
    fun `malformed ids with controls and extra separators never match and removal is harmless`() {
        val store = RegisteredPayeeStore(MemoryStore(), salt)
        val malformed = listOf(
            "@bank", "name@", "name@@bank", "name@bank@extra", "name withspace@bank",
            "name\u0000@bank", "name@bank
", " @bank", "name@ bank"
        )

        malformed.forEach { value ->
            assertFalse("unexpected match for malformed value", store.matches(value))
            store.remove(value)
        }
        assertTrue(store.isEmpty())
    }

    @Test
    fun `different salts cannot recognize persisted payee and never expose raw identifier`() {
        val backend = MemoryStore()
        val firstSaltStore = RegisteredPayeeStore(backend, salt)
        firstSaltStore.register("secretmerchant@okaxis")

        val persisted = backend.values.values.joinToString("|")
        assertFalse(persisted.contains("secretmerchant"))
        val otherSalt = ByteArray(16) { (it + 31).toByte() }
        val otherStore = RegisteredPayeeStore(backend, otherSalt)
        assertFalse(otherStore.matches("secretmerchant@okaxis"))
        assertEquals(listOf("sec***@okaxis"), firstSaltStore.maskedList())
    }

    @Test
    fun `truncated and hostile persisted data remains masked and later writes preserve valid entries`() {
        val backend = MemoryStore()
        backend.values[RegisteredPayeeStoreKeys.IDS] =
            "${"c".repeat(64)},${"d".repeat(64)},${"C".repeat(64)},short,${"c".repeat(64)}"
        backend.values[RegisteredPayeeStoreKeys.MASKED] =
            "good***@bank,raw@bank,attacker
 text,"

        val restored = RegisteredPayeeStore(backend, salt)
        assertEquals(2, restored.count()) // uppercase and duplicate identifiers are rejected
        assertEquals(listOf("good***@bank", "***"), restored.maskedList())
        restored.register("newshop@ybl")

        val afterWrite = RegisteredPayeeStore(backend, salt)
        assertEquals(3, afterWrite.count())
        assertEquals(listOf("good***@bank", "***", "new***@ybl"), afterWrite.maskedList())
    }

    @Test
    fun `clear survives reconstruction and repeated lifecycle operations`() {
        val backend = MemoryStore()
        RegisteredPayeeStore(backend, salt).apply {
            register("first@okaxis")
            register("second@ybl")
            clear()
            clear()
        }

        val afterClear = RegisteredPayeeStore(backend, salt)
        assertTrue(afterClear.isEmpty())
        assertEquals(0, afterClear.count())
        assertEquals(emptyList<String>(), afterClear.maskedList())
        assertFalse(afterClear.matches("first@okaxis"))
        afterClear.register("third@bank")
        assertEquals(listOf("thi***@bank"), RegisteredPayeeStore(backend, salt).maskedList())
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
