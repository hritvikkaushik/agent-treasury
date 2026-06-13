package tech.treasury.reconcile;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import tech.treasury.reputation.Erc8004Properties;

import java.util.Optional;

/** Reads tx receipts from Fuji. Active only with real settlement ({@code x402.enabled=true}). */
@Component
@ConditionalOnProperty(name = "x402.enabled", havingValue = "true")
public class Web3jReceiptStatusProvider implements ReceiptStatusProvider {

    private final Web3j web3j;

    public Web3jReceiptStatusProvider(Erc8004Properties props) {
        this.web3j = Web3j.build(new HttpService(props.rpcUrl()));
    }

    @Override
    public TxStatus statusOf(String txHash) {
        try {
            Optional<TransactionReceipt> receipt =
                    web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();
            if (receipt.isEmpty()) {
                return TxStatus.NOT_FOUND;
            }
            return "0x1".equals(receipt.get().getStatus()) ? TxStatus.CONFIRMED : TxStatus.FAILED;
        } catch (Exception e) {
            return TxStatus.NOT_FOUND;
        }
    }
}
