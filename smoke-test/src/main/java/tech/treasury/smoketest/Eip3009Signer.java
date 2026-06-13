package tech.treasury.smoketest;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Sign;
import org.web3j.crypto.StructuredDataEncoder;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signs an EIP-3009 {@code transferWithAuthorization} as EIP-712 typed data — the crux of the x402
 * "exact" scheme client. This class is intentionally dependency-light and self-contained so it ports
 * directly into the Spring Boot treasury (Phase 2) unchanged.
 *
 * <p>The two values that silently break signatures are the EIP-712 domain {@code name} and
 * {@code version}; for Fuji USDC these are "USD Coin" / "2" (verified on-chain — see SPIKE-FINDINGS.md).
 */
public final class Eip3009Signer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Eip3009Signer() {
    }

    /** EIP-712 domain for the token being paid in. */
    public record Eip712Domain(String name, String version, long chainId, String verifyingContract) {
    }

    /**
     * The EIP-3009 authorization. {@code value}, {@code validAfter}, {@code validBefore} are decimal
     * strings (atomic units / unix seconds); {@code nonce} is a 0x-prefixed 32-byte hex string.
     */
    public record Authorization(String from, String to, String value,
                                String validAfter, String validBefore, String nonce) {
    }

    /**
     * Signs the authorization and returns the 65-byte signature as 0x-hex, packed {@code r || s || v}
     * with {@code v} in {27, 28} (what USDC's FiatTokenV2 ECDSA expects).
     */
    public static String sign(Authorization auth, Eip712Domain domain, String privateKeyHex) {
        try {
            String typedDataJson = buildTypedDataJson(auth, domain);
            StructuredDataEncoder encoder = new StructuredDataEncoder(typedDataJson);
            byte[] digest = encoder.hashStructuredData();

            ECKeyPair keyPair = Credentials.create(privateKeyHex).getEcKeyPair();
            // needToHash=false: the EIP-712 digest is already the message to sign.
            Sign.SignatureData sig = Sign.signMessage(digest, keyPair, false);

            byte[] packed = new byte[65];
            System.arraycopy(sig.getR(), 0, packed, 0, 32);
            System.arraycopy(sig.getS(), 0, packed, 32, 32);
            packed[64] = sig.getV()[0]; // web3j returns v as 27/28 already
            return Numeric.toHexString(packed);
        } catch (Exception e) {
            throw new RuntimeException("EIP-712 / EIP-3009 signing failed", e);
        }
    }

    private static String buildTypedDataJson(Authorization auth, Eip712Domain domain) throws Exception {
        Map<String, Object> types = new LinkedHashMap<>();
        types.put("EIP712Domain", List.of(
                field("name", "string"),
                field("version", "string"),
                field("chainId", "uint256"),
                field("verifyingContract", "address")));
        types.put("TransferWithAuthorization", List.of(
                field("from", "address"),
                field("to", "address"),
                field("value", "uint256"),
                field("validAfter", "uint256"),
                field("validBefore", "uint256"),
                field("nonce", "bytes32")));

        Map<String, Object> domainMap = new LinkedHashMap<>();
        domainMap.put("name", domain.name());
        domainMap.put("version", domain.version());
        domainMap.put("chainId", domain.chainId());
        domainMap.put("verifyingContract", domain.verifyingContract());

        // uint256 fields as BigInteger (numbers) so the encoder gets canonical types; nonce stays hex.
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("from", auth.from());
        message.put("to", auth.to());
        message.put("value", new BigInteger(auth.value()));
        message.put("validAfter", new BigInteger(auth.validAfter()));
        message.put("validBefore", new BigInteger(auth.validBefore()));
        message.put("nonce", auth.nonce());

        Map<String, Object> typed = new LinkedHashMap<>();
        typed.put("types", types);
        typed.put("primaryType", "TransferWithAuthorization");
        typed.put("domain", domainMap);
        typed.put("message", message);

        return MAPPER.writeValueAsString(typed);
    }

    private static Map<String, String> field(String name, String type) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("type", type);
        return f;
    }
}
