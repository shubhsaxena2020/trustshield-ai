package ai.trustshield.android.data.preferences

import ai.trustshield.android.core.payment.maskedUpi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Gate for the persistent registered-payee store (`data/preferences/RegisteredPayeeStore.kt`).
 *
 * These are JVM unit tests against the **real** store and the real [maskedUpi] helper, driven through
 * an in-memory [InMemoryPayeeStore]. The fake is deliberately dumb — a map and a write counter — so
 * every assertion about *what lands in the key-value file* is an assertion about the real persisted
 * bytes, not about a mock's expectations.
 *
 * Three properties are load bearing and each has a dedicated test:
 *  - the raw UPI id never reaches storage, no return value or an exception message;
 *  - comparison is case/whitespace insensitive, so `'Shop@OkAxis '` and `'shop@okaxis'` are one payee;
 *  - corrupt or torn stored content degrades to "nothing registered" instead of throwing, because a
 *    preference file must never crash the QR Guard.
 *
 * Fixture VPAs are invented (`shop@okaxis`, `raj@ybl`, ...) — no real payee of any kind.
 */
class RegisteredPayeeStoreTest {

    private val salt = ByteArray(16) { (it + 1).toByte() }
    private val otherSalt = ByteArray(16) { (it + 101).toByte() }
    private val rawUpi = "shop@okaxis"
    private val expectedMasked = "sho***@okaxis"

    private fun newStore(fake: InMemoryPayeeStore, salt: ByteArray = this.salt) =
        RegisteredPayeeStore(fake, salt)

    // ------------------------------------------------------------- register / matches round trip ----

    @Test
    fun `register then matches finds the payee`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)

        store.register(rawUpi)

        assertTrue(store.matches(rawUpi))
        assertTrue(store.matches("SHOP@OKAXIS"))
        assertFalse(store.matches("raj@ybl"))
        assertEquals(1, store.count())
        assertFalse(store.isEmpty())
    }

    @Test
    fun `a fresh store over an empty backend matches nothing`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)

        assertTrue(store.isEmpty())
        assertEquals(0, store.count())
        assertFalse(store.matches(rawUpi))
        assertEquals(emptyList<String>(), store.maskedList())
    }

    @Test
    fun `case and surrounding space normalise to the same payee`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)

        store.register("  Shop@OkAxis  ")

        assertEquals(1, store.count())
        assertTrue(store.matches("shop@okaxis"))
        assertTrue(store.matches("SHOP@OKAXIS "))
        assertTrue(store.matches("  shop@okaxis"))
    }

    @Test
    fun `duplicate registration in a different case keeps a single entry`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)

        store.register(rawUpi)
        store.register("Shop@OkAxis ")
        store.register("SHOP@OKAXIS")

        assertEquals(1, store.count())
        assertEquals(listOf(expectedMasked), store.maskedList())
    }

    @Test
    fun `duplicate registration does not rewrite the backend`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)

        store.register(rawUpi)
        assertEquals(2, fake.writeCount) // ids + masked, once

        store.register(rawUpi)
        assertEquals(2, fake.writeCount)
    }

    @Test
    fun `several payees all match and keep insertion order`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)

        store.register(rawUpi)
        store.register("raj@ybl")
        store.register("meena@paytm")

        assertEquals(3, store.count())
        assertTrue(store.matches(rawUpi))
        assertTrue(store.matches("raj@ybl"))
        assertTrue(store.matches("meena@paytm"))
        assertEquals(listOf(expectedMasked, "raj***@ybl", "mee***@paytm"), store.maskedList())
    }

    // ------------------------------------------------------------------- masked display is safe ----

    @Test
    fun `maskedList shows the masked form only`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)

        store.register(rawUpi)

        assertEquals(listOf("sho***@okaxis"), store.maskedList())
    }

    @Test
    fun `maskedList is normalised so the same payee always renders identically`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)

        store.register("SHOP@OkAxis")

        assertEquals(listOf(expectedMasked), store.maskedList())
    }

    @Test
    fun `maskedList never contains the raw payee`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)

        store.register(rawUpi)
        store.register("raj@ybl")

        for (masked in store.maskedList()) {
            assertNotEquals(rawUpi, masked)
            assertFalse(masked.contains(rawUpi))
            assertFalse(masked.contains("shop@"))
        }
    }

    // -------------------------------------------------------------------------- what is persisted ----

    @Test
    fun `the backend never holds the raw upi id`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)

        store.register(rawUpi)
        store.register("raj@ybl")

        assertEquals(2, fake.values.size)
        for (value in fake.values.values) {
            assertFalse("stored value leaked a raw payee", value.contains(rawUpi))
            assertFalse(value.contains("raj@ybl"))
            assertFalse(value.contains("shop"))
        }
    }

    @Test
    fun `persisted ids are 64 hex characters and carry no at sign`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)

        store.register(rawUpi)

        val ids = fake.values[RegisteredPayeeStoreKeys.IDS]
        assertNotNull(ids)
        val identifier = requireNotNull(ids).trim()
        assertEquals(64, identifier.length)
        assertTrue(identifier.all { it in '0'..'9' || it in 'a'..'f' })
        assertFalse(identifier.contains('@'))
    }

    @Test
    fun `only the two documented keys are written`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)

        store.register(rawUpi)

        assertEquals(
            setOf(RegisteredPayeeStoreKeys.IDS, RegisteredPayeeStoreKeys.MASKED),
            fake.values.keys.toSet()
        )
        assertEquals("payee_ids", RegisteredPayeeStoreKeys.IDS)
        assertEquals("payee_masked", RegisteredPayeeStoreKeys.MASKED)
        assertEquals("trustshield_registered_payees", RegisteredPayeeStoreKeys.FILE_NAME)
    }

    @Test
    fun `persisted ids are stable for the same input and salt`() {
        val first = InMemoryPayeeStore()
        val second = InMemoryPayeeStore()

        newStore(first).register(rawUpi)
        newStore(second).register("  SHOP@okaxis ")

        assertEquals(
            first.values[RegisteredPayeeStoreKeys.IDS],
            second.values[RegisteredPayeeStoreKeys.IDS]
        )
    }

    @Test
    fun `a different salt produces a different identifier`() {
        val first = InMemoryPayeeStore()
        val second = InMemoryPayeeStore()

        newStore(first).register(rawUpi)
        newStore(second, otherSalt).register(rawUpi)

        assertNotEquals(
            first.values[RegisteredPayeeStoreKeys.IDS],
            second.values[RegisteredPayeeStoreKeys.IDS]
        )
    }

    @Test
    fun `different payees hash to different identifiers`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)

        store.register(rawUpi)
        store.register("raj@ybl")

        val ids = requireNotNull(fake.values[RegisteredPayeeStoreKeys.IDS]).split(",")
        assertEquals(2, ids.size)
        assertNotEquals(ids[0], ids[1])
    }

    @Test
    fun `two stores over the same backend see each other's writes`() {
        val fake = InMemoryPayeeStore()
        val writer = newStore(fake)
        val reader = newStore(fake)

        writer.register(rawUpi)
        assertTrue(reader.matches(rawUpi))
        assertEquals(1, reader.count())

        reader.register("raj@ybl")
        assertEquals(2, writer.count())
        assertTrue(writer.matches("raj@ybl"))

        writer.remove(rawUpi)
        assertFalse(reader.matches(rawUpi))
        assertEquals(listOf("raj***@ybl"), reader.maskedList())
    }

    // ------------------------------------------------------------------- remove / clear / count ----

    @Test
    fun `remove forgets only the named payee`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)

        store.register(rawUpi)
        store.register("raj@ybl")
        store.remove("Shop@OkAxis")

        assertEquals(1, store.count())
        assertFalse(store.matches(rawUpi))
        assertTrue(store.matches("raj@ybl"))
        assertEquals(listOf("raj***@ybl"), store.maskedList())
    }

    @Test
    fun `removing an unknown or non-upi value is a no-op`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)
        store.register(rawUpi)
        val writesBefore = fake.writeCount

        store.remove("nobody@nowhere")
        store.remove("notaupi")

        assertEquals(1, store.count())
        assertEquals(writesBefore, fake.writeCount)
        assertTrue(store.matches(rawUpi))
    }

    @Test
    fun `clear empties the store and drops both keys`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)

        store.register(rawUpi)
        store.register("raj@ybl")
        store.clear()

        assertTrue(store.isEmpty())
        assertEquals(0, store.count())
        assertEquals(emptyList<String>(), store.maskedList())
        assertFalse(fake.values.containsKey(RegisteredPayeeStoreKeys.IDS))
        assertFalse(fake.values.containsKey(RegisteredPayeeStoreKeys.MASKED))
        assertFalse(store.matches(rawUpi))
    }

    @Test
    fun `count tracks registrations and removals`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)

        assertEquals(0, store.count())
        store.register("raj@ybl")
        assertEquals(1, store.count())
        store.register(rawUpi)
        assertEquals(2, store.count())
        store.remove("raj@ybl")
        assertEquals(1, store.count())
        store.remove(rawUpi)
        assertEquals(0, store.count())
        assertTrue(store.isEmpty())
    }

    // ------------------------------------------------------------------------------- bad input ----

    @Test
    fun `register rejects a value that is not a upi id and the message carries no value`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)

        try {
            store.register("notaupi")
            fail("register accepted a value with no '@'")
        } catch (expected: IllegalArgumentException) {
            val message = expected.message.orEmpty()
            assertFalse("exception message leaked the rejected value", message.contains("notaupi"))
            assertTrue(message.contains("local@handle"))
        }
        assertTrue(store.isEmpty())
        assertEquals(0, fake.writeCount)
    }

    @Test
    fun `register rejects an empty handle`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)

        try {
            store.register("shop@")
            fail("register accepted an empty handle")
        } catch (expected: IllegalArgumentException) {
            assertFalse(expected.message.orEmpty().contains("shop"))
        }
        assertEquals(0, store.count())
    }

    @Test
    fun `matches is lenient about junk instead of throwing on the camera path`() {
        val fake = InMemoryPayeeStore()
        val store = newStore(fake)
        store.register(rawUpi)

        assertFalse(store.matches("notaupi"))
        assertFalse(store.matches(""))
        assertFalse(store.matches("@okaxis"))
        assertTrue(store.matches(rawUpi))
    }

    // -------------------------------------------------------------------- defensive persistence ----

    @Test
    fun `a corrupt stored id list does not crash and yields no payees`() {
        val fake = InMemoryPayeeStore()
        fake.values[RegisteredPayeeStoreKeys.IDS] = "id1,,id2"
        fake.values[RegisteredPayeeStoreKeys.MASKED] = "sho***@okaxis,raj***@ybl"

        val store = newStore(fake)

        assertEquals(0, store.count())
        assertTrue(store.isEmpty())
        assertEquals(emptyList<String>(), store.maskedList())
        assertFalse(store.matches(rawUpi))
    }

    @Test
    fun `empty and whitespace stored strings parse to an empty store`() {
        val fake = InMemoryPayeeStore()
        fake.values[RegisteredPayeeStoreKeys.IDS] = ""
        fake.values[RegisteredPayeeStoreKeys.MASKED] = "   "

        val store = newStore(fake)

        assertTrue(store.isEmpty())
        assertEquals(emptyList<String>(), store.maskedList())
    }

    @Test
    fun `a missing masked entry keeps the payee matched and masks to stars`() {
        val fake = InMemoryPayeeStore()
        newStore(fake).register(rawUpi)
        fake.values.remove(RegisteredPayeeStoreKeys.MASKED)

        val store = newStore(fake)

        assertEquals(1, store.count())
        assertTrue(store.matches(rawUpi))
        assertEquals(listOf(RegisteredPayeeStore.UNKNOWN_MASKED), store.maskedList())
    }

    @Test
    fun `a second masked entry missing does not shift the surviving ids`() {
        val fake = InMemoryPayeeStore()
        newStore(fake).register(rawUpi)
        newStore(fake).register("raj@ybl")
        val ids = requireNotNull(fake.values[RegisteredPayeeStoreKeys.IDS])
        val firstMasked = maskedUpi(rawUpi)
        fake.values[RegisteredPayeeStoreKeys.MASKED] = firstMasked

        val store = newStore(fake)

        assertEquals(2, store.count())
        assertTrue(store.matches("raj@ybl"))
        assertEquals(listOf(firstMasked, RegisteredPayeeStore.UNKNOWN_MASKED), store.maskedList())
    }

    @Test
    fun `a comma inside a masked value survives the persistence round trip`() {
        val fake = InMemoryPayeeStore()
        val odd = "shop@ok,axis"

        newStore(fake).register(odd)

        // The separator is escaped on write, so the two persisted lists stay parallel.
        assertEquals("sho***@ok%2Caxis", fake.values[RegisteredPayeeStoreKeys.MASKED])
        val reloaded = newStore(fake)
        assertEquals(1, reloaded.count())
        assertTrue(reloaded.matches(odd))
        assertEquals(listOf("sho***@ok,axis"), reloaded.maskedList())
    }

    @Test
    fun `a percent sign inside a masked value survives the persistence round trip`() {
        val fake = InMemoryPayeeStore()
        val odd = "shop@ok%axis"

        newStore(fake).register(odd)
        val reloaded = newStore(fake)

        assertEquals(1, reloaded.count())
        assertTrue(reloaded.matches(odd))
        assertEquals(listOf("sho***@ok%axis"), reloaded.maskedList())
    }

    // --------------------------------------------------------------------------------- the salt ----

    @Test
    fun `a salt shorter than sixteen bytes is refused`() {
        val fake = InMemoryPayeeStore()

        for (size in listOf(0, 1, 15)) {
            try {
                RegisteredPayeeStore(fake, ByteArray(size))
                fail("accepted a $size byte salt")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message.orEmpty().contains("at least 16 bytes"))
            }
        }
        assertEquals(0, fake.writeCount)
    }

    @Test
    fun `a sixteen byte salt is accepted`() {
        val fake = InMemoryPayeeStore()

        val store = RegisteredPayeeStore(fake, ByteArray(16) { 7 })

        store.register(rawUpi)
        assertTrue(store.matches(rawUpi))
    }

    /**
     * The fake the whole suite runs on: a map plus a write counter. Deliberately has no crypto, no
     * normalisation and no opinion, so a test failure can only be the store's fault.
     */
    private class InMemoryPayeeStore : PayeeKeyValueStore {

        val values: MutableMap<String, String> = LinkedHashMap()

        var writeCount: Int = 0
            private set

        override fun getString(key: String): String? = values[key]

        override fun putString(key: String, value: String) {
            values[key] = value
            writeCount += 1
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }
}
