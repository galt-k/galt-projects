package org.killbill.billing.payment.core;

import org.example.plugin.MockAuthorizationPlugin;
import org.example.plugin.PaymentPluginAPI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Profile("mock")
@Import(RetryConfiguration.class)
public class MockAuthorizationConfiguration {
    @Bean
    @ConditionalOnMissingBean(PaymentPluginAPI.class)
    public MockAuthorizationPlugin paymentPlugin(
            @Value("${payment.authorization.mock.outcome:SUCCESS}") MockAuthorizationPlugin.Outcome outcome,
            @Value("${payment.authorization.mock.transient-failures:0}") int transientFailures) {
        return new MockAuthorizationPlugin(outcome, transientFailures);
    }
}
