package org.taniwha.service;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

// Signs proxied trusted-node requests and verifies signed responses using shared-secret HMAC headers.
@Service
public class TrustedNodeSignatureService {

    public static final String REQUEST_TIMESTAMP_HEADER = "X-Taniwha-Timestamp";
    public static final String REQUEST_NONCE_HEADER = "X-Taniwha-Nonce";
    public static final String REQUEST_CONTENT_HASH_HEADER = "X-Taniwha-Content-SHA256";
    public static final String REQUEST_SIGNATURE_HEADER = "X-Taniwha-Signature";

    public static final String RESPONSE_TIMESTAMP_HEADER = "X-Taniwha-Response-Timestamp";
    public static final String RESPONSE_CONTENT_HASH_HEADER = "X-Taniwha-Response-Content-SHA256";
    public static final String RESPONSE_SIGNATURE_HEADER = "X-Taniwha-Response-Signature";

    public Optional<SignedRequestContext> signRequestIfNeeded(HttpMethod method,
                                                              URI targetUri,
                                                              HttpHeaders headers,
                                                              byte[] body,
                                                              Optional<String> sharedSecret) {
        if (sharedSecret.isEmpty()) {
            return Optional.empty();
        }

        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String pathAndQuery = canonicalPathAndQuery(targetUri);
        String contentHash = sha256Base64(body);
        String signature = hmacBase64(
                sharedSecret.get(),
                requestCanonical(method.name(), pathAndQuery, contentHash, timestamp, nonce)
        );

        headers.set(REQUEST_TIMESTAMP_HEADER, timestamp);
        headers.set(REQUEST_NONCE_HEADER, nonce);
        headers.set(REQUEST_CONTENT_HASH_HEADER, contentHash);
        headers.set(REQUEST_SIGNATURE_HEADER, signature);

        return Optional.of(new SignedRequestContext(method.name(), pathAndQuery, nonce, sharedSecret.get()));
    }

    public boolean verifyResponseIfNeeded(SignedRequestContext context,
                                          HttpStatusCode statusCode,
                                          HttpHeaders headers,
                                          byte[] body) {
        if (context == null) {
            return true;
        }

        String timestamp = headers.getFirst(RESPONSE_TIMESTAMP_HEADER);
        String contentHash = headers.getFirst(RESPONSE_CONTENT_HASH_HEADER);
        String signature = headers.getFirst(RESPONSE_SIGNATURE_HEADER);
        if (isBlank(timestamp) || isBlank(contentHash) || isBlank(signature)) {
            return false;
        }

        if (!contentHash.equals(sha256Base64(body))) {
            return false;
        }

        String expectedSignature = hmacBase64(
                context.sharedSecret(),
                responseCanonical(statusCode.value(), context.method(), context.pathAndQuery(), context.requestNonce(), timestamp, contentHash)
        );
        return MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                expectedSignature.getBytes(StandardCharsets.UTF_8)
        );
    }

    public String canonicalPathAndQuery(URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() == null || uri.getRawQuery().isBlank()) {
            return path;
        }
        return path + "?" + uri.getRawQuery();
    }

    public String sha256Base64(byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] normalizedBody = body == null ? new byte[0] : body;
            return Base64.getEncoder().encodeToString(digest.digest(normalizedBody));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String requestCanonical(String method, String pathAndQuery, String contentHash, String timestamp, String nonce) {
        return String.join("\n", "REQ", method, pathAndQuery, contentHash, timestamp, nonce);
    }

    private String responseCanonical(int statusCode, String method, String pathAndQuery, String requestNonce, String timestamp, String contentHash) {
        return String.join("\n", "RESP", Integer.toString(statusCode), method, pathAndQuery, requestNonce, timestamp, contentHash);
    }

    private String hmacBase64(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate request signature", e);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record SignedRequestContext(String method, String pathAndQuery, String requestNonce, String sharedSecret) {
    }
}
