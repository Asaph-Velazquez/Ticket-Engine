package com.ticketengine.crypto

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TicketCryptoServiceTest {
    private val keys = generateKeys()
    private val crypto = TicketCryptoService(keys.private, keys.public)
    private val claims = mapOf<String, Any>("sub" to "usuario-1", "eventId" to "evento-1", "iat" to 1_700_000_000L)
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    @Test
    fun `SHA256 coincide con vector publicado abc`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            crypto.sha256Hex("abc".toByteArray()))
    }

    @Test
    fun `serializacion estable ordena objetos y conserva UTF8 y enteros`() {
        val first = linkedMapOf<String, Any>("z" to listOf(1, true, "México"), "a" to mapOf("d" to 2, "b" to "fila A"))
        val second = linkedMapOf<String, Any>("a" to mapOf("b" to "fila A", "d" to 2L), "z" to listOf(1L, true, "México"))
        assertEquals("{\"a\":{\"b\":\"fila A\",\"d\":2},\"z\":[1,true,\"México\"]}", crypto.canonicalJson(first))
        assertEquals(crypto.canonicalJson(first), crypto.canonicalJson(second))
        assertEquals(crypto.sign(first).payloadHash(), crypto.sign(second).payloadHash())
    }

    @Test
    fun `rechaza flotantes null Unicode invalido y payload excesivo`() {
        assertFailsWith<IllegalArgumentException> { crypto.sign(mapOf("amount" to 1.5)) }
        assertFailsWith<IllegalArgumentException> { crypto.sign(mapOf("empty" to null)) }
        assertFailsWith<IllegalArgumentException> { crypto.sign(mapOf("broken" to "\uD800")) }
        assertFailsWith<IllegalArgumentException> { crypto.sign(mapOf("big" to "a".repeat(17_000))) }
    }

    @Test
    fun `JWS usa ES256 estandar y firma raw de 64 bytes verificada sin nuestro conversor DER`() {
        val signed = crypto.sign(claims)
        val parts = signed.token().split('.')
        assertEquals(3, parts.size)
        assertEquals(64, decoder.decode(parts[2]).size)
        assertEquals(signed.payloadJson(), String(decoder.decode(parts[1]), StandardCharsets.UTF_8))
        assertEquals(crypto.sha256Hex(signed.payloadJson().toByteArray()), signed.payloadHash())
        val standard = Signature.getInstance("SHA256withECDSAinP1363Format")
        standard.initVerify(keys.public)
        standard.update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
        assertTrue(standard.verify(decoder.decode(parts[2])))
        assertEquals("usuario-1", crypto.verify(signed.token())["sub"])
    }

    @Test
    fun `alterar payload firma o header invalida el boleto`() {
        val parts = crypto.sign(claims).token().split('.')
        val otherPayload = encoder.encodeToString("{\"sub\":\"atacante\"}".toByteArray())
        assertFailsWith<IllegalArgumentException> { crypto.verify("${parts[0]}.$otherPayload.${parts[2]}") }
        val signature = decoder.decode(parts[2])
        signature[0] = (signature[0].toInt() xor 1).toByte()
        assertFailsWith<IllegalArgumentException> {
            crypto.verify("${parts[0]}.${parts[1]}.${encoder.encodeToString(signature)}")
        }
        val badHeader = encoder.encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\",\"kid\":\"${crypto.keyId()}\"}".toByteArray())
        assertFailsWith<IllegalArgumentException> { crypto.verify("$badHeader.${parts[1]}.${parts[2]}") }
    }

    @Test
    fun `otro emisor no verifica una firma y no se aceptan parejas mezcladas`() {
        val other = generateKeys()
        val otherCrypto = TicketCryptoService(other.private, other.public)
        assertNotEquals(crypto.keyId(), otherCrypto.keyId())
        assertFailsWith<IllegalArgumentException> { otherCrypto.verify(crypto.sign(claims).token()) }
        assertFailsWith<IllegalArgumentException> { TicketCryptoService(keys.private, other.public) }
    }

    @Test
    fun `rechaza curva distinta a P256`() {
        val wrongCurve = generateKeys("secp384r1")
        assertFailsWith<IllegalArgumentException> { TicketCryptoService(wrongCurve.private, wrongCurve.public) }
    }

    @Test
    fun `JWK solo publica parametros de verificacion y kid estable`() {
        val jwk = crypto.publicJwk()
        assertEquals("EC", jwk["kty"])
        assertEquals("P-256", jwk["crv"])
        assertEquals("ES256", jwk["alg"])
        assertEquals(crypto.keyId(), jwk["kid"])
        assertEquals(listOf("verify"), jwk["key_ops"])
        assertEquals(32, decoder.decode(jwk["x"] as String).size)
        assertEquals(32, decoder.decode(jwk["y"] as String).size)
        assertFalse(jwk.containsKey("d"))
        assertEquals(crypto.keyId(), TicketCryptoService(keys.private, keys.public).keyId())
    }

    @Test
    fun `reestablecer PEM conserva identidad y verifica boletos previos`(@TempDir directory: Path) {
        val privatePath = directory.resolve("private.pem")
        val publicPath = directory.resolve("public.pem")
        writePem(privatePath, "PRIVATE KEY", keys.private.encoded)
        writePem(publicPath, "PUBLIC KEY", keys.public.encoded)
        val beforeRestart = crypto.sign(claims).token()
        val restored = TicketCryptoService.load(privatePath, publicPath)
        assertEquals(crypto.keyId(), restored.keyId())
        assertEquals("usuario-1", restored.verify(beforeRestart)["sub"])
        assertEquals("usuario-1", crypto.verify(restored.sign(claims).token())["sub"])
    }

    @Test
    fun `rechaza llaves PEM invalidas sin filtrar su contenido`(@TempDir directory: Path) {
        val privatePath = directory.resolve("invalid.pem")
        val publicPath = directory.resolve("public.pem")
        Files.writeString(privatePath, "-----BEGIN PRIVATE KEY-----\nSECRETOINVALIDO\n-----END PRIVATE KEY-----")
        writePem(publicPath, "PUBLIC KEY", keys.public.encoded)
        val error = assertFailsWith<IllegalStateException> { TicketCryptoService.load(privatePath, publicPath) }
        assertFalse(error.message!!.contains("SECRETOINVALIDO"))
        assertEquals(null, error.cause)
    }

    @Test
    fun `rechaza tokens malformados base64 no canonico y firmas cero`() {
        val signed = crypto.sign(claims).token()
        val parts = signed.split('.')
        val invalidTokens = listOf("", ".", "a.b.c.d", "a.b.c", "x".repeat(24_001),
            "${parts[0]}=.${parts[1]}.${parts[2]}",
            "${parts[0]}.${parts[1]}.${encoder.encodeToString(ByteArray(64))}",
            "${parts[0]}.${parts[1]}.${encoder.encodeToString(ByteArray(63))}")
        invalidTokens.forEach { token -> assertFailsWith<IllegalArgumentException> { crypto.verify(token) } }
    }

    @Test
    fun `rechaza aun con firma autentica JSON duplicado no canonico y headers inesperados`() {
        val header = "{\"alg\":\"ES256\",\"kid\":\"${crypto.keyId()}\",\"typ\":\"JWT\"}"
        val payloads = listOf("{\"sub\":\"a\",\"sub\":\"b\"}", "{ \"sub\": \"a\" }", "{\"sub\":\"a\"} {}", "{\"a\":1.0}", "null", "[]")
        payloads.forEach { payload ->
            assertFailsWith<IllegalArgumentException> { crypto.verify(externalSign(header, payload)) }
        }
        val headers = listOf(
            "{\"alg\":\"ES256\",\"kid\":\"${crypto.keyId()}\",\"typ\":\"JWT\",\"jku\":\"https://invalid.example/key\"}",
            "{\"alg\":\"ES256\",\"kid\":\"${crypto.keyId()}\",\"typ\":\"JWT\",\"alg\":\"ES256\"}",
            "{\"alg\":\"ES256\",\"kid\":\"wrong\",\"typ\":\"JWT\"}")
        headers.forEach { badHeader ->
            assertFailsWith<IllegalArgumentException> { crypto.verify(externalSign(badHeader, "{\"sub\":\"a\"}")) }
        }
    }

    @Test
    fun `firma y verifica concurrentemente sin compartir estado mutable`() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = (1..40).map { n -> Callable {
                val token = crypto.sign(mapOf("sub" to "usuario-$n")).token()
                assertEquals("usuario-$n", crypto.verify(token)["sub"])
            } }
            pool.invokeAll(tasks).forEach { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun externalSign(header: String, payload: String): String {
        val input = encoder.encodeToString(header.toByteArray()) + "." + encoder.encodeToString(payload.toByteArray())
        val signer = Signature.getInstance("SHA256withECDSAinP1363Format")
        signer.initSign(keys.private)
        signer.update(input.toByteArray(StandardCharsets.US_ASCII))
        return input + "." + encoder.encodeToString(signer.sign())
    }

    private fun writePem(path: Path, label: String, encoded: ByteArray) {
        Files.writeString(path, "-----BEGIN $label-----\n${Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(encoded)}\n-----END $label-----\n")
    }

    private fun generateKeys(curve: String = "secp256r1"): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()
}
