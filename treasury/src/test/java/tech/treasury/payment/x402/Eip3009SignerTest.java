package tech.treasury.payment.x402;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure signing checks (no network). The live settlement path is verified by the smoke test. */
class Eip3009SignerTest {

    // A well-known throwaway test key (Hardhat account #0). NOT a real funded key.
    private static final String TEST_KEY =
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80";
    private static final String FUJI_USDC = "0x5425890298aed601595a70AB815c96711a31Bc65";

    @Test
    void producesA65ByteSignature() {
        var auth = new Eip3009Signer.Authorization(
                "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                "0x6f409644a8a0b598284e8ca1a7562759f2189fbf",
                "10000", "1781334466", "1781335366",
                "0xaba1d417335c420c90a361dc715d6d64bde677957a5c8082d8371f3574b4dd3b");
        var domain = new Eip3009Signer.Eip712Domain("USD Coin", "2", 43113, FUJI_USDC);

        String sig = Eip3009Signer.sign(auth, domain, TEST_KEY);

        assertThat(sig).startsWith("0x");
        assertThat(sig).hasSize(132); // 0x + 65 bytes * 2 hex chars
        int v = Integer.parseInt(sig.substring(130), 16);
        assertThat(v).isIn(27, 28); // recovery id USDC's ECDSA expects
    }

    @Test
    void isDeterministicForFixedInputs() {
        var auth = new Eip3009Signer.Authorization(
                "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                "0x6f409644a8a0b598284e8ca1a7562759f2189fbf",
                "10000", "1781334466", "1781335366",
                "0xaba1d417335c420c90a361dc715d6d64bde677957a5c8082d8371f3574b4dd3b");
        var domain = new Eip3009Signer.Eip712Domain("USD Coin", "2", 43113, FUJI_USDC);

        assertThat(Eip3009Signer.sign(auth, domain, TEST_KEY))
                .isEqualTo(Eip3009Signer.sign(auth, domain, TEST_KEY));
    }
}
