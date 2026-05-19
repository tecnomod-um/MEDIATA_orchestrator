package org.taniwha.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TrustedNodeSignatureServiceTest {

    private final TrustedNodeSignatureService service = new TrustedNodeSignatureService();

    @Test
    void signRequestIfNeeded_returnsEmptyWhenNoSharedSecret() {
        HttpHeaders headers = new HttpHeaders();

        Optional<TrustedNodeSignatureService.SignedRequestContext> context =
                service.signRequestIfNeeded(
                        HttpMethod.GET,
                        URI.create("http://node/taniwha/api/files"),
                        headers,
                        null,
                        Optional.empty()
                );

        assertTrue(context.isEmpty());
        assertFalse(headers.containsKey(TrustedNodeSignatureService.REQUEST_SIGNATURE_HEADER));
    }

    @Test
    void signRequestIfNeeded_addsCanonicalRequestHeadersAndContext() {
        HttpHeaders headers = new HttpHeaders();
        byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);

        TrustedNodeSignatureService.SignedRequestContext context =
                service.signRequestIfNeeded(
                                HttpMethod.POST,
                                URI.create("http://node/taniwha/api/files?limit=5"),
                                headers,
                                body,
                                Optional.of("shared-secret")
                        )
                        .orElseThrow();

        assertEquals("POST", context.method());
        assertEquals("/taniwha/api/files?limit=5", context.pathAndQuery());
        assertEquals("shared-secret", context.sharedSecret());
        assertNotNull(context.requestNonce());
        assertEquals(service.sha256Base64(body),
                headers.getFirst(TrustedNodeSignatureService.REQUEST_CONTENT_HASH_HEADER));
        assertNotNull(headers.getFirst(TrustedNodeSignatureService.REQUEST_TIMESTAMP_HEADER));
        assertNotNull(headers.getFirst(TrustedNodeSignatureService.REQUEST_NONCE_HEADER));
        assertNotNull(headers.getFirst(TrustedNodeSignatureService.REQUEST_SIGNATURE_HEADER));
    }

    @Test
    void verifyResponseIfNeeded_acceptsMatchingSignedResponse() {
        HttpHeaders requestHeaders = new HttpHeaders();
        byte[] requestBody = "request".getBytes(StandardCharsets.UTF_8);
        TrustedNodeSignatureService.SignedRequestContext context =
                service.signRequestIfNeeded(
                                HttpMethod.POST,
                                URI.create("http://node/taniwha/node/validate"),
                                requestHeaders,
                                requestBody,
                                Optional.of("shared-secret")
                        )
                        .orElseThrow();

        byte[] responseBody = "{\"jwtNodeToken\":\"NODE\"}".getBytes(StandardCharsets.UTF_8);
        HttpHeaders responseHeaders = signedResponseHeaders(
                context,
                HttpStatus.OK.value(),
                "1700000000",
                responseBody,
                "shared-secret"
        );

        assertTrue(service.verifyResponseIfNeeded(context, HttpStatus.OK, responseHeaders, responseBody));
    }

    @Test
    void verifyResponseIfNeeded_rejectsMissingSignatureHeaders() {
        TrustedNodeSignatureService.SignedRequestContext context =
                new TrustedNodeSignatureService.SignedRequestContext(
                        "GET",
                        "/taniwha/api/files",
                        "nonce",
                        "shared-secret"
                );

        assertFalse(service.verifyResponseIfNeeded(context, HttpStatus.OK, new HttpHeaders(), new byte[0]));
    }

    @Test
    void verifyResponseIfNeeded_rejectsTamperedResponseBody() {
        TrustedNodeSignatureService.SignedRequestContext context =
                new TrustedNodeSignatureService.SignedRequestContext(
                        "GET",
                        "/taniwha/api/files",
                        "nonce",
                        "shared-secret"
                );
        byte[] originalBody = "original".getBytes(StandardCharsets.UTF_8);
        HttpHeaders responseHeaders = signedResponseHeaders(
                context,
                HttpStatus.OK.value(),
                "1700000000",
                originalBody,
                "shared-secret"
        );

        assertFalse(service.verifyResponseIfNeeded(
                context,
                HttpStatus.OK,
                responseHeaders,
                "changed".getBytes(StandardCharsets.UTF_8)
        ));
    }

    @Test
    void verifyResponseIfNeeded_rejectsWrongSharedSecret() {
        TrustedNodeSignatureService.SignedRequestContext context =
                new TrustedNodeSignatureService.SignedRequestContext(
                        "GET",
                        "/taniwha/api/files",
                        "nonce",
                        "shared-secret"
                );
        byte[] responseBody = "body".getBytes(StandardCharsets.UTF_8);
        HttpHeaders responseHeaders = signedResponseHeaders(
                context,
                HttpStatus.OK.value(),
                "1700000000",
                responseBody,
                "other-secret"
        );

        assertFalse(service.verifyResponseIfNeeded(context, HttpStatus.OK, responseHeaders, responseBody));
    }

    @Test
    void canonicalPathAndQuery_defaultsEmptyPathToSlash() {
        assertEquals("/", service.canonicalPathAndQuery(URI.create("http://node")));
        assertEquals("/taniwha/api?q=a%20b", service.canonicalPathAndQuery(
                URI.create("http://node/taniwha/api?q=a%20b")
        ));
    }

    private HttpHeaders signedResponseHeaders(TrustedNodeSignatureService.SignedRequestContext context,
                                              int statusCode,
                                              String timestamp,
                                              byte[] body,
                                              String secret) {
        String contentHash = service.sha256Base64(body);
        String payload = String.join("\n",
                "RESP",
                Integer.toString(statusCode),
                context.method(),
                context.pathAndQuery(),
                context.requestNonce(),
                timestamp,
                contentHash
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set(TrustedNodeSignatureService.RESPONSE_TIMESTAMP_HEADER, timestamp);
        headers.set(TrustedNodeSignatureService.RESPONSE_CONTENT_HASH_HEADER, contentHash);
        headers.set(TrustedNodeSignatureService.RESPONSE_SIGNATURE_HEADER, hmacBase64(secret, payload));
        return headers;
    }

    private String hmacBase64(String secret, String payload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    secret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            ));
            return java.util.Base64.getEncoder().encodeToString(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
