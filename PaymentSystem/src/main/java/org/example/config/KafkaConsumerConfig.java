package org.example.config;

import org.example.PaymentItem;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

@Configuration
public class KafkaConsumerConfig {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentItem> batchKafkaListenerContainerFactory(
            ConsumerFactory<String, PaymentItem> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, PaymentItem> factory = new ConcurrentKafkaListenerContainerFactory<>();

        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);


        factory.getContainerProperties().setListenerTaskExecutor(new VirtualThreadTaskExecutor("kafka-db-writer"));
        return factory;
    }
}
