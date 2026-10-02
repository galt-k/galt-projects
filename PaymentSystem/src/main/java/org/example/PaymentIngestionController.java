package org.example;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentIngestionController {
    private final PaymentIngestionService ingestionService;

    public PaymentIngestionController(PaymentIngestionService paymentIngestionService) {
        this.ingestionService = paymentIngestionService;
    }

    //Q4: Map PostRequest with JSon payload
    public ResponseEntity<IngestionResult> ingestBatch(HttpServletRequest request) throws Exception{
        //grabe the unparsed socket stream directly from tomcat
        InputStream inputStream = request.getInputStream();

        IngestionResult result = ingestionService.ingestBatch(inputStream);

        return ResponseEntity.accepted().body(result);
    }
}
