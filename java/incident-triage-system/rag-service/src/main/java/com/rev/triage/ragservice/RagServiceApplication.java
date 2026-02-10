package com.rev.triage.ragservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class RagServiceApplication {

    public static void main(String[] args) {
        // Resolve LangChain4j HTTP client conflict: both Spring RestClient and JDK HttpClient
        // are on the classpath. Tell LangChain4j to use the Spring RestClient implementation.
        System.setProperty("langchain4j.http.clientBuilderFactory",
                "dev.langchain4j.http.client.spring.restclient.SpringRestClientBuilderFactory");
        SpringApplication.run(RagServiceApplication.class, args);
    }
}
