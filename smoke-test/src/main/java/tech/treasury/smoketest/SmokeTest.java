package tech.treasury.smoketest;

import com.fasterxml.jackson.databind.JsonNode;
import org.web3j.crypto.Credentials;
import org.web3j.utils.Numeric;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase-0 go/no-go gate. Signs one EIP-3009 USDC payment on Avalanche Fuji and pushes it through the
 * x402.rs facilitator: GET /supported → POST /verify → POST /settle, then prints the Snowtrace link.
 *
 * <p>Proves the EIP-712 domain, the v-packing, the wire format, and the facilitator envelope are all
 * correct BEFORE any treasury code is built on top. Config comes from env vars (see .env.example).
 */
public class SmokeTest {

    public static void main(String[] args) throws Exception {
        String privateKey  = req("TREASURY_PRIVATE_KEY");
        String payTo       = req("PAY_TO");
        String facilitator = env("FACILITATOR_URL", "http://localhost:8080");
        String usdc        = env("USDC_ADDRESS", "0x5425890298aed601595a70AB815c96711a31Bc65");
        String amount      = env("AMOUNT", "10000");                 // 0.01 USDC (6 decimals)
        String network     = env("NETWORK", "avalanche-fuji");       // fallback: eip155:43113
        long chainId       = Long.parseLong(env("CHAIN_ID", "43113"));
        String domainName  = env("USDC_DOMAIN_NAME", "USD Coin");
        String domainVer   = env("USDC_DOMAIN_VERSION", "2");
        int timeoutSecs    = Integer.parseInt(env("MAX_TIMEOUT_SECONDS", "300"));

        String from = Credentials.create(privateKey).getAddress();
        long now = Instant.now().getEpochSecond();
        String validAfter  = Long.toString(now - 600);
        String validBefore = Long.toString(now + timeoutSecs);
        String nonce = randomNonce();

        System.out.println("== x402 / EIP-3009 Phase-0 smoke test (Avalanche Fuji) ==");
        System.out.println("from (treasury): " + from);
        System.out.println("to   (payTo)   : " + payTo);
        System.out.println("amount         : " + amount + " atomic (0.01 USDC @ 6dp)");
        System.out.println("network        : " + network);
        System.out.println("facilitator    : " + facilitator);
        System.out.println();

        Eip3009Signer.Authorization auth = new Eip3009Signer.Authorization(
                from, payTo, amount, validAfter, validBefore, nonce);
        Eip3009Signer.Eip712Domain domain = new Eip3009Signer.Eip712Domain(
                domainName, domainVer, chainId, usdc);

        String signature = Eip3009Signer.sign(auth, domain, privateKey);
        System.out.println("signature: " + signature);

        Map<String, Object> payload = X402Payment.payload(1, "exact", network, signature, auth);
        System.out.println("X-PAYMENT: " + X402Payment.header(payload));
        System.out.println();

        Map<String, Object> requirements = requirements(network, amount, payTo, usdc,
                timeoutSecs, domainName, domainVer);
        FacilitatorClient client = new FacilitatorClient(facilitator);

        System.out.println("--- GET /supported ---");
        try {
            System.out.println(client.supported().toPrettyString());
        } catch (Exception e) {
            System.out.println("(could not reach facilitator: " + e.getMessage() + ")");
            System.out.println("Is x402.rs running? See infra/facilitator/README.md");
            return;
        }
        System.out.println();

        System.out.println("--- POST /verify ---");
        JsonNode verify = client.verify(payload, requirements);
        System.out.println(verify.toPrettyString());
        if (!verify.path("isValid").asBoolean(false)) {
            System.out.println();
            System.out.println("VERIFY FAILED. Try the known-risk knobs (RESUME-PLAN.md):");
            System.out.println("  1. NETWORK=eip155:43113   2. envelope/field names   3. domain name/version");
            return;
        }
        System.out.println();

        System.out.println("--- POST /settle ---");
        JsonNode settle = client.settle(payload, requirements);
        System.out.println(settle.toPrettyString());
        if (settle.path("success").asBoolean(false)) {
            String tx = settle.path("transaction").asText();
            System.out.println();
            System.out.println("SETTLED ✓  https://testnet.snowtrace.io/tx/" + tx);
            System.out.println("Phase-0 gate passed — the signing + settlement path works end to end.");
        } else {
            System.out.println();
            System.out.println("SETTLE FAILED: " + settle.path("errorReason").asText());
            System.out.println("Common causes: treasury wallet has no test USDC, or facilitator wallet has no AVAX for gas.");
        }
    }

    private static Map<String, Object> requirements(String network, String amount, String payTo,
            String asset, int timeoutSecs, String name, String version) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("name", name);
        extra.put("version", version);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("scheme", "exact");
        r.put("network", network);
        r.put("maxAmountRequired", amount);   // v1 field name; v2 uses "amount"
        r.put("resource", "https://smoke-test.local/resource");
        r.put("description", "Phase-0 smoke test");
        r.put("payTo", payTo);
        r.put("asset", asset);
        r.put("maxTimeoutSeconds", timeoutSecs);
        r.put("extra", extra);
        return r;
    }

    private static String randomNonce() {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        return Numeric.toHexString(b);
    }

    private static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    private static String req(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required env var: " + key + " (see .env.example)");
        }
        return v;
    }
}
