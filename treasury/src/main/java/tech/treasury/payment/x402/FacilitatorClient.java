package tech.treasury.payment.x402;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import tech.treasury.payment.PaymentExecutor.ExecutionResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Calls the x402.rs facilitator's {@code /settle} (verify + on-chain settlement). */
public class FacilitatorClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final String baseUrl;

    public FacilitatorClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public ExecutionResult settle(Map<String, Object> paymentPayload, Map<String, Object> requirements) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("x402Version", 1);
            envelope.put("paymentPayload", paymentPayload);
            envelope.put("paymentRequirements", requirements);

            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/settle"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(envelope)))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode body = MAPPER.readTree(res.body());

            if (body.path("success").asBoolean(false)) {
                return ExecutionResult.ok(body.path("transaction").asText());
            }
            String reason = body.path("errorReason").asText(null);
            String message = body.path("errorMessage").asText(null);
            return ExecutionResult.failed(
                    "settle failed (HTTP " + res.statusCode() + "): "
                            + (reason != null ? reason : "unknown")
                            + (message != null ? " — " + message : ""));
        } catch (Exception e) {
            return ExecutionResult.failed("facilitator call failed: " + e.getMessage());
        }
    }
}
