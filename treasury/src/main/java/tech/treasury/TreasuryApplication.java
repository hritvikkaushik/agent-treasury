package tech.treasury;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import tech.treasury.payment.x402.X402Properties;
import tech.treasury.reputation.Erc8004Properties;

@SpringBootApplication
@EnableConfigurationProperties({X402Properties.class, Erc8004Properties.class})
public class TreasuryApplication {

    public static void main(String[] args) {
        SpringApplication.run(TreasuryApplication.class, args);
    }
}
