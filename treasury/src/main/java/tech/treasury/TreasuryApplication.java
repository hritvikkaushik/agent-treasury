package tech.treasury;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import tech.treasury.payment.x402.X402Properties;
import tech.treasury.reputation.Erc8004Properties;

@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties({X402Properties.class, Erc8004Properties.class})
public class TreasuryApplication {

    public static void main(String[] args) {
        SpringApplication.run(TreasuryApplication.class, args);
    }
}
