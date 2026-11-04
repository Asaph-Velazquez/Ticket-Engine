package com.ticketengine.crypto;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Native P-256 signer. Instances keep keys private and allocate Signature per operation. */
public final class TicketCryptoService {
    private static final int MAX_PAYLOAD_BYTES = 16_384;
    private static final int MAX_TOKEN_LENGTH = 24_000;
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64_DECODER = Base64.getUrlDecoder();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(16).maxStringLength(MAX_PAYLOAD_BYTES).maxNumberLength(20).build())
            .build());

    private final ECPrivateKey privateKey;
    private final ECPublicKey publicKey;
    private final String keyId;

    public TicketCryptoService(PrivateKey privateKey, PublicKey publicKey) {
        if (!(privateKey instanceof ECPrivateKey ecPrivate) || !(publicKey instanceof ECPublicKey ecPublic)) {
            throw new IllegalArgumentException("Se requieren llaves EC P-256");
        }
        try {
            ECParameterSpec expected = p256Parameters();
            requireP256(ecPrivate.getParams(), expected);
            requireP256(ecPublic.getParams(), expected);
            if (ecPrivate.getS().signum() <= 0 || ecPrivate.getS().compareTo(expected.getOrder()) >= 0) {
                throw new IllegalArgumentException("Escalar privado inválido");
            }
            requirePoint(ecPublic, expected);
            this.privateKey = ecPrivate;
            this.publicKey = ecPublic;
            // A fixed domain-separated challenge only checks that both configured files form a pair.
            byte[] challenge = "TicketEngine/key-pair-check/v1".getBytes(StandardCharsets.US_ASCII);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(ecPrivate);
            signer.update(challenge);
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(ecPublic);
            verifier.update(challenge);
            if (!verifier.verify(signer.sign())) {
                throw new IllegalArgumentException("Las llaves no forman una pareja");
            }
            String thumbprint = "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\""
                    + B64.encodeToString(unsigned32(ecPublic.getW().getAffineX()))
                    + "\",\"y\":\"" + B64.encodeToString(unsigned32(ecPublic.getW().getAffineY())) + "\"}";
            this.keyId = B64.encodeToString(digest(thumbprint.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalArgumentException("No se pudieron validar las llaves P-256", ex);
        }
    }

    /** Load configured PKCS#8 PRIVATE KEY and X.509 SubjectPublicKeyInfo PUBLIC KEY PEM files. */
    public static TicketCryptoService load(Path privatePkcs8Pem, Path publicSpkiPem) {
        byte[] privateDer = null;
        try {
            KeyFactory factory = KeyFactory.getInstance("EC");
            privateDer = pem(privatePkcs8Pem, "PRIVATE KEY");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privateDer));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(pem(publicSpkiPem, "PUBLIC KEY")));
            return new TicketCryptoService(privateKey, publicKey);
        } catch (IOException | GeneralSecurityException ex) {
            // Deliberately excludes file contents and underlying exception messages.
            throw new IllegalStateException("No se pudieron cargar las llaves de firma configuradas");
        } finally {
            if (privateDer != null) {
                Arrays.fill(privateDer, (byte) 0);
            }
        }
    }

    public String keyId() {
        return keyId;
    }

    public Map<String, Object> publicJwk() {
        return Map.of("kty", "EC", "crv", "P-256", "alg", "ES256", "use", "sig",
                "kid", keyId, "x", B64.encodeToString(unsigned32(publicKey.getW().getAffineX())),
                "y", B64.encodeToString(unsigned32(publicKey.getW().getAffineY())), "key_ops", List.of("verify"));
    }

    public String sha256Hex(byte[] payload) {
        if (payload == null) {
            throw new IllegalArgumentException("Payload requerido");
        }
        return HexFormat.of().formatHex(digest(payload));
    }

    public String canonicalJson(Map<String, Object> claims) {
        if (claims == null || claims.isEmpty()) {
            throw new IllegalArgumentException("Claims requeridos");
        }
        byte[] payload = canonicalBytes(claims);
        if (payload.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("Payload demasiado grande");
        }
        return new String(payload, StandardCharsets.UTF_8);
    }

    public SignedTicket sign(Map<String, Object> claims) {
        if (claims == null || claims.isEmpty()) {
            throw new IllegalArgumentException("Claims requeridos");
        }
        try {
            byte[] payload = canonicalJson(claims).getBytes(StandardCharsets.UTF_8);
            byte[] header = canonicalBytes(Map.of("alg", "ES256", "typ", "JWT", "kid", keyId));
            String signingInput = B64.encodeToString(header) + "." + B64.encodeToString(payload);
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            signer.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            String token = signingInput + "." + B64.encodeToString(derToJose(signer.sign()));
            return new SignedTicket(token, new String(payload, StandardCharsets.UTF_8), sha256Hex(payload));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("No se pudo firmar el boleto", ex);
        }
    }

    /** Signature + exact protected header + canonical JSON. Application validates claim semantics. */
    public Map<String, Object> verify(String token) {
        try {
            if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
                throw invalid();
            }
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || parts[0].length() > 1024) {
                throw invalid();
            }
            byte[] headerBytes = decode(parts[0]);
            Map<String, Object> header = readMap(headerBytes);
            if (!header.equals(Map.of("alg", "ES256", "typ", "JWT", "kid", keyId))) {
                throw invalid();
            }
            byte[] signature = decode(parts[2]);
            if (signature.length != 64) {
                throw invalid();
            }
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!verifier.verify(joseToDer(signature))) {
                throw invalid();
            }
            byte[] payload = decode(parts[1]);
            if (payload.length > MAX_PAYLOAD_BYTES) {
                throw invalid();
            }
            Map<String, Object> claims = readMap(payload);
            if (claims.isEmpty() || !Arrays.equals(payload, canonicalBytes(claims))) {
                throw invalid();
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(claims));
        } catch (IOException | GeneralSecurityException | RuntimeException ex) {
            throw invalid();
        }
    }

    public record SignedTicket(String token, String payloadJson, String payloadHash) { }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Boleto firmado inválido");
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 no disponible", ex);
        }
    }

    private static byte[] canonicalBytes(Object value) {
        try {
            return JSON.writeValueAsBytes(normalize(value, 0));
        } catch (IOException ex) {
            throw new IllegalArgumentException("Payload no serializable");
        }
    }

    private static Object normalize(Object value, int depth) {
        if (depth > 12) {
            throw new IllegalArgumentException("Payload demasiado anidado");
        }
        if (value instanceof String text) {
            requireUnicode(text);
            return text;
        }
        if (value instanceof Boolean) {
            return value;
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new IllegalArgumentException("Las llaves JSON deben ser texto");
                }
                requireUnicode(key);
                sorted.put(key, normalize(entry.getValue(), depth + 1));
            }
            return sorted;
        }
        if (value instanceof List<?> list) {
            List<Object> items = new ArrayList<>(list.size());
            for (Object item : list) {
                items.add(normalize(item, depth + 1));
            }
            return items;
        }
        throw new IllegalArgumentException("Solo se permiten textos, enteros de 64 bits, booleanos, objetos y listas");
    }

    private static void requireUnicode(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("Texto Unicode inválido");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("Texto Unicode inválido");
            }
        }
    }

    private static Map<String, Object> readMap(byte[] bytes) throws IOException {
        // readValue alone accepts trailing JSON; explicitly check parser exhaustion.
        try (var parser = JSON.createParser(bytes)) {
            Map<String, Object> result = JSON.readValue(parser, MAP_TYPE);
            if (result == null || parser.nextToken() != null) {
                throw invalid();
            }
            return result;
        }
    }

    private static byte[] decode(String part) {
        if (part.isEmpty() || !part.matches("[A-Za-z0-9_-]+")) {
            throw invalid();
        }
        byte[] result = B64_DECODER.decode(part);
        if (!B64.encodeToString(result).equals(part)) {
            throw invalid();
        }
        return result;
    }

    private static byte[] pem(Path path, String label) throws IOException {
        if (Files.size(path) > 16_384) {
            throw new IllegalArgumentException("Archivo PEM demasiado grande");
        }
        String text = Files.readString(path, StandardCharsets.US_ASCII).trim();
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        if (!text.startsWith(begin) || !text.endsWith(end)) {
            throw new IllegalArgumentException("Formato PEM no soportado");
        }
        String body = text.substring(begin.length(), text.length() - end.length()).replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }

    private static ECParameterSpec p256Parameters() throws GeneralSecurityException {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        return parameters.getParameterSpec(ECParameterSpec.class);
    }

    private static void requireP256(ECParameterSpec actual, ECParameterSpec expected) {
        if (actual == null || !actual.getCurve().equals(expected.getCurve())
                || !actual.getGenerator().equals(expected.getGenerator())
                || !actual.getOrder().equals(expected.getOrder()) || actual.getCofactor() != expected.getCofactor()) {
            throw new IllegalArgumentException("Solo se permite la curva secp256r1");
        }
    }

    private static void requirePoint(ECPublicKey key, ECParameterSpec parameters) {
        BigInteger p = ((ECFieldFp) parameters.getCurve().getField()).getP();
        BigInteger x = key.getW().getAffineX();
        BigInteger y = key.getW().getAffineY();
        if (x == null || y == null || x.signum() < 0 || y.signum() < 0 || x.compareTo(p) >= 0 || y.compareTo(p) >= 0
                || !y.multiply(y).mod(p).equals(x.multiply(x).multiply(x)
                .add(parameters.getCurve().getA().multiply(x)).add(parameters.getCurve().getB()).mod(p))) {
            throw new IllegalArgumentException("Punto público inválido");
        }
    }

    private static byte[] unsigned32(BigInteger value) {
        byte[] bytes = value.toByteArray();
        int start = bytes.length == 33 && bytes[0] == 0 ? 1 : 0;
        if (bytes.length - start > 32 || value.signum() < 0) {
            throw invalid();
        }
        byte[] result = new byte[32];
        System.arraycopy(bytes, start, result, 32 - (bytes.length - start), bytes.length - start);
        return result;
    }

    private static byte[] derToJose(byte[] der) {
        if (der.length < 8 || der.length > 72 || der[0] != 0x30 || (der[1] & 0xff) != der.length - 2) {
            throw invalid();
        }
        byte[] jose = new byte[64];
        int pos = 2;
        for (int offset : new int[]{0, 32}) {
            if (pos + 2 > der.length || der[pos++] != 0x02) {
                throw invalid();
            }
            int length = der[pos++] & 0xff;
            if (length < 1 || length > 33 || pos + length > der.length || (der[pos] & 0x80) != 0
                    || (length > 1 && der[pos] == 0 && (der[pos + 1] & 0x80) == 0)) {
                throw invalid();
            }
            BigInteger value = new BigInteger(1, Arrays.copyOfRange(der, pos, pos + length));
            checkScalar(value);
            System.arraycopy(unsigned32(value), 0, jose, offset, 32);
            pos += length;
        }
        if (pos != der.length) {
            throw invalid();
        }
        return jose;
    }

    private static byte[] joseToDer(byte[] jose) {
        ByteArrayOutputStream integers = new ByteArrayOutputStream(70);
        for (int offset : new int[]{0, 32}) {
            BigInteger value = new BigInteger(1, Arrays.copyOfRange(jose, offset, offset + 32));
            checkScalar(value);
            byte[] bytes = value.toByteArray();
            integers.write(0x02);
            integers.write(bytes.length);
            integers.writeBytes(bytes);
        }
        ByteArrayOutputStream sequence = new ByteArrayOutputStream(72);
        sequence.write(0x30);
        sequence.write(integers.size());
        sequence.writeBytes(integers.toByteArray());
        return sequence.toByteArray();
    }

    private static void checkScalar(BigInteger value) {
        BigInteger order = new BigInteger("ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551", 16);
        if (value.signum() <= 0 || value.compareTo(order) >= 0) {
            throw invalid();
        }
    }
}
