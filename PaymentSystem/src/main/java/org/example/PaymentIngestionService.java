package org.example;

import com.fasterxml.jackson.core.JsonFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.springframework.kafka.support.SendResult;

@Service
public class PaymentIngestionService {

    private final PaymentValidator validator;
    private final JsonFactory jsonFactory;
    //need a reference to kafkaTemplate
    private final KafkaTemplate<String, String> kafkaTemplate;

    public PaymentIngestionService(ObjectMapper objectMapper, PaymentValidator validator, KafkaTemplate<String, String> kafkaTemplate) {
        this.validator = validator;
        this.jsonFactory = objectMapper.getFactory();
        this.kafkaTemplate = kafkaTemplate;
    }

    /*
    Parse the input bytes using Jackson parser.
    Create PaymentItem records.
    Valdiate the payment rules.
    Ingest to DB.
     */
    public IngestionResult ingestBatch(InputStream inputStream) throws Exception{
        List<CompletableFuture<SendResult<String, PaymentItem>>> futures = new ArrayList<>();
        long failedCount = 0L;

        //Stream parsing logic and JDBC batch insertion
        //Create a shortlived, per request JsonParser from the singleton factory
        try(JsonParser parser = jsonFactory.createParser(inputStream)){
            while(parser.nextToken() != null && parser.currentToken() != JsonToken.END_OBJECT){
                if("items".equals(parser.currentName())) {
                    parser.nextToken();

                    while(parser.nextToken() != null && parser.currentToken() != JsonToken.START_OBJECT){
                        PaymentItem item = parser.readValueAs(PaymentItem.class);

                        //2. Synchronus validation
                        if(validator.isAmountValid(item)){
                            //3. Fast non-blocking handoff to kafak recordaacummulator
                            CompletableFuture<SendResult<String, PaymentItem>> future = kafkaTemplate.send(KAFKA_TOPIC, item);

                            futures.add(future);
                        }
                    }
                }
            }
        }

        //wait until all the futures are having the result
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        return new IngestionResult("BATCH_OK");
        //return new IngestionResult(futures.size(), failedCount);
    }
}
