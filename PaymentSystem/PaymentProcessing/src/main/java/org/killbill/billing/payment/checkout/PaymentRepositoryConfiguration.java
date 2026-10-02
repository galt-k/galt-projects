package org.killbill.billing.payment.checkout;

import org.example.persistence.FilePaymentRepository;
import org.example.persistence.PaymentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class PaymentRepositoryConfiguration {
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(PaymentRepository.class)
    public FilePaymentRepository paymentRepository(
            @Value("${payment.store.path:data/payments.json}") String path) {
        return new FilePaymentRepository(Path.of(path));
    }
}
