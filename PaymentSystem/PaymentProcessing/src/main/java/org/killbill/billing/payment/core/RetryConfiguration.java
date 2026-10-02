package org.killbill.billing.payment.core;

import org.example.retry.AuthorizationRetryClassifier;
import org.example.retry.DefaultRetryExecutor;
import org.example.retry.LedgerRetryClassifier;
import org.example.retry.RetryAnnotationPostProcessor;
import org.example.retry.RetryProperties;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RetryProperties.class)
public class RetryConfiguration {
    @Bean
    public static RetryAnnotationPostProcessor retryAnnotations(BeanFactory beans) {
        return new RetryAnnotationPostProcessor(beans);
    }

    @Bean
    public RetryAnnotationPostProcessor.PolicyRegistry retryPolicies(RetryProperties properties) {
        return new RetryAnnotationPostProcessor.PolicyRegistry(properties.resolve());
    }

    @Bean(destroyMethod = "close")
    public DefaultRetryExecutor retryExecutor() { return new DefaultRetryExecutor(); }

    @Bean
    public AuthorizationRetryClassifier authorizationRetryClassifier() { return new AuthorizationRetryClassifier(); }

    @Bean
    public LedgerRetryClassifier ledgerRetryClassifier() { return new LedgerRetryClassifier(); }
}
