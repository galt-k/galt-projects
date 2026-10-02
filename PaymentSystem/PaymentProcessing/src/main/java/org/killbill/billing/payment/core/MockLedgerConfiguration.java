package org.killbill.billing.payment.core;

import org.example.ledger.LedgerStatus;
import org.example.ledger.MockLedgerServer;
import org.example.ledger.RestLedgerClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Import;

import java.io.IOException;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@Profile("mock-ledger")
@Import(RetryConfiguration.class)
public class MockLedgerConfiguration {
    @Bean(destroyMethod = "close")
    public MockLedgerServer mockLedgerServer(@Value("${payment.ledger.mock.port:0}") int port,
                                             @Value("${payment.ledger.mock.outcome:SUCCESS}") LedgerStatus outcome,
                                             @Value("${payment.ledger.mock.transient-failures:0}") int transientFailures)
            throws IOException {
        return new MockLedgerServer(port, outcome, request -> {}, Duration.ZERO, transientFailures);
    }

    @Bean(destroyMethod = "close")
    public RestLedgerClient ledgerClient(MockLedgerServer server,
                                         @Value("${payment.ledger.timeout:PT2S}") String timeout) {
        return new RestLedgerClient(server.endpoint(), Duration.parse(timeout), true);
    }
}
