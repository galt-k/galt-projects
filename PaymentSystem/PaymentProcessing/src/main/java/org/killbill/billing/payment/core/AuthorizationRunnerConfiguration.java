package org.killbill.billing.payment.core;

import org.example.persistence.PaymentRepository;
import org.example.persistence.PaymentTransactionRepository;
import org.example.plugin.PaymentPluginAPI;
import org.example.ledger.LedgerClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AuthorizationRunnerConfiguration {
    @Bean
    @ConditionalOnMissingBean(PaymentAutomatonRunner.class)
    public PaymentAutomatonRunner paymentAutomatonRunner(PaymentRepository payments,
                                                        PaymentTransactionRepository transactions,
                                                        PaymentPluginAPI plugin,
                                                        ObjectProvider<LedgerClient> ledgerProvider,
                                                        @Value("${payment.plugin.name:mock}") String pluginName) {
        LedgerClient ledger = ledgerProvider.getIfAvailable();
        return ledger == null
                ? new AuthorizationAutomatonRunner(payments, transactions, plugin, pluginName)
                : new DefaultPaymentAutomatonRunner(payments, transactions, plugin, ledger, pluginName);
    }
}
