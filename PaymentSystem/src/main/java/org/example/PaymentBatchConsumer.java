package org.example;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class PaymentBatchConsumer {
    private final PaymentRepository paymentRepository;

    public PaymentBatchConsumer(PaymentRespository paymentRespository){
        this.paymentRepository = paymentRespository;
    }

    @KafkaListener(topics = "payment-ingestion-wal")
    public void consumeBatch(List<PaymentItem> items, Acknowledgment ack) {
        paymentRepository.saveBatchIdempotent(items);
        ack.acknowledge();
    }
}
