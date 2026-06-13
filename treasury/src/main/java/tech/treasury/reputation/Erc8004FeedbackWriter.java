package tech.treasury.reputation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Int128;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;

import java.math.BigInteger;
import java.util.List;

/**
 * Writes positive feedback to the ERC-8004 reputation registry after a successful payment, closing
 * the trust loop. Async + best-effort: failures are logged, never propagated to the payment.
 * Active when {@code erc8004.enabled=true}.
 */
@Component
@Primary
@ConditionalOnProperty(name = "erc8004.enabled", havingValue = "true")
public class Erc8004FeedbackWriter implements FeedbackWriter {

    private static final Logger log = LoggerFactory.getLogger(Erc8004FeedbackWriter.class);
    private static final BigInteger FEEDBACK_SCORE = BigInteger.valueOf(100); // satisfied payment
    private static final BigInteger GAS_LIMIT = BigInteger.valueOf(300_000);

    private final Web3j web3j;
    private final String identityRegistry;
    private final String reputationRegistry;
    private final RawTransactionManager txManager;

    public Erc8004FeedbackWriter(Erc8004Properties props) {
        if (isBlank(props.treasuryPrivateKey())) {
            throw new IllegalStateException("erc8004.enabled=true but TREASURY_PRIVATE_KEY is not set");
        }
        this.web3j = Web3j.build(new HttpService(props.rpcUrl()));
        this.identityRegistry = props.identityRegistry();
        this.reputationRegistry = props.reputationRegistry();
        this.txManager = new RawTransactionManager(
                web3j, Credentials.create(props.treasuryPrivateKey()), props.chainId());
    }

    @Override
    @Async
    public void recordSuccessfulPayment(String payee) {
        try {
            BigInteger agentId = agentIdOf(payee);
            if (agentId.signum() == 0) {
                log.debug("no agentId for {} — skipping feedback", payee);
                return;
            }
            Function giveFeedback = new Function("giveFeedback",
                    List.of(new Uint256(agentId),
                            new Int128(FEEDBACK_SCORE),
                            new Uint8(BigInteger.ZERO),
                            new Utf8String("quality"),
                            new Utf8String(""),
                            new Utf8String(""),
                            new Utf8String(""),
                            new Bytes32(new byte[32])),
                    List.of());
            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
            EthSendTransaction tx = txManager.sendTransaction(
                    gasPrice, GAS_LIMIT, reputationRegistry, FunctionEncoder.encode(giveFeedback), BigInteger.ZERO);
            if (tx.hasError()) {
                log.warn("feedback tx rejected for agent {}: {}", agentId, tx.getError().getMessage());
            } else {
                log.info("wrote feedback (score {}) for agent {} -> tx {}",
                        FEEDBACK_SCORE, agentId, tx.getTransactionHash());
            }
        } catch (Exception e) {
            log.warn("feedback write failed for {} (best-effort): {}", payee, e.getMessage());
        }
    }

    private BigInteger agentIdOf(String wallet) throws Exception {
        Function fn = new Function("agentIdOf",
                List.of(new Address(wallet)),
                List.of(new TypeReference<Uint256>() {
                }));
        EthCall resp = web3j.ethCall(
                Transaction.createEthCallTransaction(null, identityRegistry, FunctionEncoder.encode(fn)),
                DefaultBlockParameterName.LATEST).send();
        List<Type> out = FunctionReturnDecoder.decode(resp.getValue(), fn.getOutputParameters());
        return (BigInteger) out.get(0).getValue();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
