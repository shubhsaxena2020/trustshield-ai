package ai.trustshield.android.core.payment

import ai.trustshield.android.data.preferences.PayeeKeyValueStore
import ai.trustshield.android.data.preferences.RegisteredPayeeStore
import ai.trustshield.android.data.preferences.RegisteredPayeeStoreKeys
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private class MemoryStore : PayeeKeyValueStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
    }
}
