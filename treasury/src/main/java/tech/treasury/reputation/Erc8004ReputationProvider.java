package tech.treasury.reputation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Int128;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.http.HttpService;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Reads counterparty reputation from the on-chain ERC-8004 registries: resolve payee address ->
 * agentId (Identity), then agentId -> average score (Reputation.getSummary). Cached briefly to keep
 * one chain round-trip from sitting on the payment path. Active when {@code erc8004.enabled=true};
 * marked {@code @Primary} so it supersedes the stub.
 *
 * <p>Fail-closed: any read error or unregistered counterparty yields null (unknown) → the policy denies.
 */
@Component
@Primary
@ConditionalOnProperty(name = "erc8004.enabled", havingValue = "true")
public class Erc8004ReputationProvider implements ReputationProvider {

    private static final Logger log = LoggerFactory.getLogger(Erc8004ReputationProvider.class);

    private final Web3j web3j;
    private final String identityRegistry;
    private final String reputationRegistry;
    private final Cache<String, Optional<Integer>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .maximumSize(1_000)
            .build();

    public Erc8004ReputationProvider(Erc8004Properties props) {
        if (isBlank(props.identityRegistry()) || isBlank(props.reputationRegistry())) {
            throw new IllegalStateException(
                    "erc8004.enabled=true but registry addresses are not set");
        }
        this.identityRegistry = props.identityRegistry();
        this.reputationRegistry = props.reputationRegistry();
        this.web3j = Web3j.build(new HttpService(props.rpcUrl()));
        log.info("ERC-8004 reputation enabled: identity={} reputation={} rpc={}",
                identityRegistry, reputationRegistry, props.rpcUrl());
    }

    @Override
    public Integer reputationOf(String counterparty) {
        if (counterparty == null || counterparty.isBlank()) {
            return null;
        }
        return cache.get(counterparty.toLowerCase(), this::readChain).orElse(null);
    }

    private Optional<Integer> readChain(String counterparty) {
        try {
            BigInteger agentId = callUint(identityRegistry,
                    new Function("agentIdOf",
                            List.of(new Address(counterparty)),
                            List.of(new TypeReference<Uint256>() {
                            })));
            if (agentId.signum() == 0) {
                return Optional.empty(); // unregistered -> unknown
            }

            Function summary = new Function("getSummary",
                    List.of(new Uint256(agentId),
                            new DynamicArray<>(Address.class, List.of()),
                            new Utf8String(""),
                            new Utf8String("")),
                    List.of(new TypeReference<Uint64>() {
                    }, new TypeReference<Int128>() {
                    }, new TypeReference<Uint8>() {
                    }));
            List<Type> out = ethCall(reputationRegistry, summary);
            BigInteger count = (BigInteger) out.get(0).getValue();
            if (count.signum() == 0) {
                return Optional.empty(); // no feedback -> unknown
            }
            BigInteger value = (BigInteger) out.get(1).getValue();
            BigInteger decimals = (BigInteger) out.get(2).getValue();
            long score = value.divide(BigInteger.TEN.pow(decimals.intValue())).longValue();
            int clamped = (int) Math.max(0, Math.min(100, score));
            return Optional.of(clamped);
        } catch (Exception e) {
            log.warn("reputation read failed for {} (treating as unknown): {}", counterparty, e.getMessage());
            return Optional.empty();
        }
    }

    private BigInteger callUint(String contract, Function fn) throws Exception {
        return (BigInteger) ethCall(contract, fn).get(0).getValue();
    }

    private List<Type> ethCall(String contract, Function fn) throws Exception {
        String data = FunctionEncoder.encode(fn);
        EthCall resp = web3j.ethCall(
                Transaction.createEthCallTransaction(null, contract, data),
                DefaultBlockParameterName.LATEST).send();
        if (resp.hasError()) {
            throw new IllegalStateException("eth_call error: " + resp.getError().getMessage());
        }
        return FunctionReturnDecoder.decode(resp.getValue(), fn.getOutputParameters());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
