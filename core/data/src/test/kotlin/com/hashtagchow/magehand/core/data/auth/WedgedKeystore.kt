package com.hashtagchow.magehand.core.data.auth

import java.security.AlgorithmParameters
import java.security.Key
import java.security.Provider
import java.security.ProviderException
import java.security.SecureRandom
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import javax.crypto.CipherSpi
import javax.crypto.SecretKey

/**
 * A wedged AndroidKeyStore, reproduced closely enough on a desktop JVM to pin the
 * failure that a `./gradlew test` run could not otherwise reach.
 *
 * **Why this is a faithful model, not a contrivance.** On-device the failure has two
 * halves that have to line up:
 *
 * 1. the key is **non-exportable** — `SecretKey.getEncoded()` on an AndroidKeyStore key
 *    returns `null`, so the AndroidKeyStore provider is the *only* provider that can do
 *    anything with it ([NonExportableAesKey]);
 * 2. that provider's cipher SPI throws [ProviderException] out of `Cipher.init` /
 *    `doFinal` when the keymaster or StrongBox is wedged ([WedgedKeystoreCipher]).
 *
 * Both halves are load-bearing. JCE does *delayed provider selection*: with an ordinary
 * exportable key, `Cipher.init` quietly walks past a throwing provider and succeeds on
 * SunJCE, and no exception ever surfaces. It is only because no other provider will
 * touch a `null`-encoded key that the first exception — ours — is the one rethrown
 * (`Cipher.chooseProvider` keeps the first failure and rethrows it unchanged when it is
 * a `RuntimeException`). That is exactly the shape of the real thing.
 *
 * Blast radius on the rest of the suite is nil even though a provider is a *global*
 * registration: with any normal key, delayed selection means SunJCE still answers.
 */
class WedgedKeystoreCipher : CipherSpi() {

    override fun engineSetMode(mode: String) = Unit

    override fun engineSetPadding(padding: String) = Unit

    override fun engineGetBlockSize(): Int = 16

    override fun engineGetOutputSize(inputLen: Int): Int = inputLen

    override fun engineGetIV(): ByteArray? = null

    override fun engineGetParameters(): AlgorithmParameters? = null

    override fun engineInit(opmode: Int, key: Key, random: SecureRandom?): Unit = wedged()

    override fun engineInit(
        opmode: Int,
        key: Key,
        params: AlgorithmParameterSpec?,
        random: SecureRandom?,
    ): Unit = wedged()

    override fun engineInit(
        opmode: Int,
        key: Key,
        params: AlgorithmParameters?,
        random: SecureRandom?,
    ): Unit = wedged()

    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray =
        wedged()

    override fun engineUpdate(
        input: ByteArray?,
        inputOffset: Int,
        inputLen: Int,
        output: ByteArray?,
        outputOffset: Int,
    ): Int = wedged()

    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray =
        wedged()

    override fun engineDoFinal(
        input: ByteArray?,
        inputOffset: Int,
        inputLen: Int,
        output: ByteArray?,
        outputOffset: Int,
    ): Int = wedged()

    /** The observed shape: an unchecked throw, from the operation rather than the key. */
    private fun wedged(): Nothing = throw ProviderException("keymaster is wedged")
}

/**
 * Registers [WedgedKeystoreCipher] for the transformation the codec asks for.
 *
 * The `double` version constructor rather than the `String` one it is deprecated in
 * favour of: unit tests compile against `android.jar`, whose `Provider` has only the
 * former. It resolves to the real JDK class at runtime, where both exist.
 */
@Suppress("DEPRECATION")
class WedgedKeystoreProvider : Provider(NAME, 1.0, "test double for a wedged keymaster") {
    init {
        put("Cipher.${AesGcmTokenCodec.TRANSFORMATION}", WedgedKeystoreCipher::class.java.name)
    }

    companion object {
        const val NAME: String = "MageHandWedgedKeystore"
    }
}

/**
 * An AndroidKeyStore-shaped key: right algorithm, no material to hand out.
 *
 * `getEncoded() == null` is not a trick — it is what every non-exportable Keystore key
 * reports, and it is the property that makes the Keystore provider the only one able to
 * use it. See [WedgedKeystoreCipher].
 */
object NonExportableAesKey : SecretKey {
    override fun getAlgorithm(): String = "AES"
    override fun getFormat(): String = "RAW"
    override fun getEncoded(): ByteArray? = null
}

/**
 * Runs [block] with the wedged provider at the front of the JCE list, and removes it
 * again however [block] ends — a leaked provider would follow the whole suite.
 *
 * `inline` so the block may suspend, which the store's `read` does.
 */
inline fun <T> withWedgedKeystore(block: () -> T): T {
    Security.insertProviderAt(WedgedKeystoreProvider(), 1)
    return try {
        block()
    } finally {
        Security.removeProvider(WedgedKeystoreProvider.NAME)
    }
}
