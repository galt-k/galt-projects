package com.rev.triage.ragservice.service;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RagQueryService {

    private static final Logger log = LoggerFactory.getLogger(RagQueryService.class);

    private final ContentRetriever contentRetriever;
    private final ChatModel chatModel;

    public RagQueryService(ContentRetriever contentRetriever,
                           ChatModel chatModel) {
        this.contentRetriever = contentRetriever;
        this.chatModel = chatModel;
    }

    public String ask(String question) {
        log.info("Received question: {}", question);

        // Step 1: Retrieve relevant chunks from ChromaDB
        List<Content> relevantContent = contentRetriever.retrieve(new Query(question));
        log.info("Retrieved {} relevant chunks from vector store", relevantContent.size());

        if (relevantContent.isEmpty()) {
            return "I don't have enough context to answer that question. " +
                   "Try ingesting documents first via POST /ingest.";
        }

        // Step 2: Build prompt with context
        String context = relevantContent.stream()
                .map(content -> content.textSegment().text())
                .collect(Collectors.joining("\n\n---\n\n"));

        String prompt = String.format("""
                You are a helpful assistant for the Incident Triage System — \
                a distributed microservices application with product-service, \
                order-service, and payment-service.

                Answer the following question based ONLY on the provided context. \
                If the context doesn't contain enough information to answer, say so.

                Context:
                %s

                Question: %s

                Answer:""", context, question);

        // Step 3: Send to Ollama chat model
        String answer = chatModel.chat(prompt);
        log.info("Generated answer for question: {}", question);

        return answer;
    }
}
