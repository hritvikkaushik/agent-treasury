package tech.treasury.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.utils.Numeric;
import tech.treasury.domain.PaymentIntent;
import tech.treasury.payment.x402.Eip3009Signer;
import tech.treasury.payment.x402.FacilitatorClient;
import tech.treasury.payment.x402.X402Properties;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Real settlement: signs an EIP-3009 USDC authorization (treasury wallet is the payer) and submits it
 * to the x402.rs facilitator's {@code /settle}, which broadcasts the on-chain transfer on Avalanche
 * Fuji. Active only when {@code x402.enabled=true}; otherwise {@link StubPaymentExecutor} is used.
 */
@Component
@ConditionalOnProperty(name = "x402.enabled", havingValue = "true")
public class X402PaymentExecutor implements PaymentExecutor {

    private static final Logger log = LoggerFactory.getLogger(X402PaymentExecutor.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final X402Properties props;
    private final FacilitatorClient facilitator;
    private final String fromAddress;

    public X402PaymentExecutor(X402Properties props) {
        if (props.treasuryPrivateKey() == null || props.treasuryPrivateKey().isBlank()) {
            throw new IllegalStateException(
                    "x402.enabled=true but TREASURY_PRIVATE_KEY is not set — cannot sign payments");
        }
        this.props = props;
        this.fromAddress = Credentials.create(props.treasuryPrivateKey()).getAddress();
        this.facilitator = new FacilitatorClient(props.facilitatorUrl());
        log.info("x402 settlement enabled: payer={} network={} facilitator={}",
                fromAddress, props.network(), props.facilitatorUrl());
    }

    @Override
    public ExecutionResult execute(PaymentIntent intent) {
        long now = Instant.now().getEpochSecond();
        String value = Long.toString(intent.getAmountAtomic());

        Eip3009Signer.Authorization auth = new Eip3009Signer.Authorization(
                fromAddress,
                intent.getPayee(),
                value,
                Long.toString(now - 600),
                Long.toString(now + props.maxTimeoutSeconds()),
                randomNonce());
        Eip3009Signer.Eip712Domain domain = new Eip3009Signer.Eip712Domain(
                props.usdcDomainName(), props.usdcDomainVersion(), props.chainId(), props.asset());

        String signature = Eip3009Signer.sign(auth, domain, props.treasuryPrivateKey());

        ExecutionResult result = facilitator.settle(payload(signature, auth), requirements(value, intent.getPayee()));
        if (result.success()) {
            log.info("settled intent {} -> tx {}", intent.getId(), result.txHash());
        } else {
            log.warn("settle failed for intent {}: {}", intent.getId(), result.error());
        }
        return result;
    }

    private Map<String, Object> payload(String signature, Eip3009Signer.Authorization auth) {
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
        payload.put("x402Version", 1);
        payload.put("scheme", "exact");
        payload.put("network", props.network());
        payload.put("payload", inner);
        return payload;
    }

    private Map<String, Object> requirements(String value, String payTo) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("name", props.usdcDomainName());
        extra.put("version", props.usdcDomainVersion());

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("scheme", "exact");
        r.put("network", props.network());
        r.put("maxAmountRequired", value);
        r.put("resource", "treasury://payment");
        r.put("description", "Agent Treasury payment");
        r.put("payTo", payTo);
        r.put("asset", props.asset());
        r.put("maxTimeoutSeconds", props.maxTimeoutSeconds());
        r.put("extra", extra);
        return r;
    }

    private static String randomNonce() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Numeric.toHexString(b);
    }
}
