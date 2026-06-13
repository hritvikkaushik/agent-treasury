package tech.treasury.smoketest;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the x402 v1 payment payload and the base64 {@code X-PAYMENT} header.
 * Schemas: SPIKE-FINDINGS.md §3.
 */
public final class X402Payment {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private X402Payment() {
    }

    /**
     * The decoded payment payload — used both as the {@code X-PAYMENT} body (pre-base64) and as the
     * {@code paymentPayload} object in the facilitator's /verify and /settle envelopes.
     */
    public static Map<String, Object> payload(int x402Version, String scheme, String network,
                                              String signature, Eip3009Signer.Authorization auth) {
        Map<String, Object> authorization = new LinkedHashMap<>();
        authorization.put("from", auth.from());
        authorization.put("to", auth.to());
        authorization.put("value", auth.value());
        authorization.put("validAfter", auth.validAfter());
        authorization.put("validBefore", auth.validBefore());
        authorization.put("nonce", auth.nonce());

        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("signature", signature);
        inner.put("authorization", authorization);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("x402Version", x402Version);
        payload.put("scheme", scheme);
        payload.put("network", network);
        payload.put("payload", inner);
        return payload;
    }

    /** Base64-encode the payload into the {@code X-PAYMENT} header value (what a real client sends). */
    public static String header(Map<String, Object> payload) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(payload);
            return Base64.getEncoder().encodeToString(json);
        } catch (Exception e) {
            throw new RuntimeException("X-PAYMENT encoding failed", e);
        }
    }
}
