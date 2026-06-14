package tech.treasury.api;

import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tech.treasury.agent.AgentService;
import tech.treasury.domain.AgentEntity;
import tech.treasury.service.TreasuryService;

import java.time.Instant;
import java.util.UUID;

/**
 * The agent-facing ingress. An agent authenticates with {@code X-Agent-Key} and asks the treasury to
 * make a payment; the treasury enforces policy, then signs + settles. Agents never hold keys.
 */
@RestController
@RequestMapping("/proxy")
public class ProxyController {

    private static final Logger log = LoggerFactory.getLogger(ProxyController.class);

    private final AgentService agentService;
    private final TreasuryService treasury;

    public ProxyController(AgentService agentService, TreasuryService treasury) {
        this.agentService = agentService;
        this.treasury = treasury;
    }

    @PostMapping
    public ResponseEntity<PaymentResult> pay(
            @RequestHeader(value = "X-Agent-Key", required = false) String apiKey,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {

        log.info("POST /proxy: payee={} amount={} idem={}",
                request.payee(), request.amountAtomic(), idempotencyKey == null ? "(auto)" : idempotencyKey);

        AgentEntity agent = agentService.authenticate(apiKey).orElseThrow(() -> {
            log.warn("POST /proxy -> 401 (invalid/missing X-Agent-Key)");
            return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid agent key");
        });

        String key = (idempotencyKey != null && !idempotencyKey.isBlank())
                ? idempotencyKey
                : UUID.randomUUID().toString();

        PaymentResult result = treasury.process(
                agent.getId(), request.payee(), request.asset(), request.amountAtomic(), key, Instant.now());

        HttpStatus status = switch (result.state()) {
            case SETTLED -> HttpStatus.OK;
            case DENIED -> HttpStatus.PAYMENT_REQUIRED; // 402: blocked by policy
            case FAILED -> HttpStatus.BAD_GATEWAY;       // 502: settlement failed downstream
            default -> HttpStatus.OK;
        };
        log.info("POST /proxy -> {} {} (agent={})", status.value(), result.state(), agent.getId());
        return ResponseEntity.status(status).body(result);
    }
}
