package tech.treasury.smoketest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thin client for the x402.rs facilitator's /verify, /settle, /supported endpoints.
 * Envelope schema: {@code { x402Version, paymentPayload, paymentRequirements }} (SPIKE-FINDINGS.md §2).
 */
public final class FacilitatorClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;

    public FacilitatorClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public JsonNode verify(Map<String, Object> payload, Map<String, Object> requirements) throws Exception {
        return post("/verify", envelope(payload, requirements));
    }

    public JsonNode settle(Map<String, Object> payload, Map<String, Object> requirements) throws Exception {
        return post("/settle", envelope(payload, requirements));
    }

    public JsonNode supported() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/supported")).GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(res.body());
    }

    private Map<String, Object> envelope(Map<String, Object> payload, Map<String, Object> requirements) {
        Map<String, Object> env = new LinkedHashMap<>();
        env.put("x402Version", 1);
        env.put("paymentPayload", payload);
        env.put("paymentRequirements", requirements);
        return env;
    }

    private JsonNode post(String path, Object body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        System.out.println("POST " + path + " -> HTTP " + res.statusCode());
        return MAPPER.readTree(res.body());
    }
}
