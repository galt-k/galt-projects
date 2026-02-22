package com.rev.triage.ragservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ingests documentation (.md files) into ChromaDB for RAG retrieval.
 *
 * Migrated from LangChain4j → Spring AI:
 *   - FileSystemDocumentLoader     → TextReader(resource)
 *   - EmbeddingStoreIngestor       → TokenTextSplitter + VectorStore.add()
 *   - embeddingStore.removeAll()   → vectorStore.delete(filterExpression)
 *   - LangChain4j Document         → Spring AI Document
 *   - LangChain4j Metadata         → Map<String, Object>
 *
 * Spring AI auto-configures:
 *   - VectorStore (ChromaVectorStore) — via spring-ai-starter-vector-store-chroma
 *   - EmbeddingModel (OllamaEmbeddingModel) — via spring-ai-ollama-spring-boot-starter
 *   The VectorStore automatically uses the EmbeddingModel to embed documents on add().
 *   No need to manually wire the embedding model — Spring AI handles it.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final VectorStore vectorStore;

    @Value("${rag.docs.path}")
    private String docsPath;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * Remove all existing documentation chunks from ChromaDB.
     * Called before re-ingestion to prevent duplicates across restarts.
     */
    public void clearExistingDocuments() {
        try {
            vectorStore.delete("type == 'documentation'");
            log.info("Cleared existing documentation chunks from ChromaDB");
        } catch (Exception e) {
            log.debug("Could not clear existing docs (collection may be empty): {}", e.getMessage());
        }
    }

    public int ingestDocuments() {
        log.info("Starting document ingestion from {}", docsPath);

        List<Document> documents = loadDocuments();
        if (documents.isEmpty()) {
            log.warn("No documents found to ingest");
            return 0;
        }

        log.info("Loaded {} documents, starting chunking and embedding...", documents.size());

        // TokenTextSplitter: 1000 tokens per chunk, min 200 chars, min 5 chars to embed
        TokenTextSplitter splitter = new TokenTextSplitter(1000, 200, 5, 10000, true);
        List<Document> chunks = splitter.split(documents);

        vectorStore.add(chunks);

        log.info("Successfully ingested {} documents ({} chunks) into ChromaDB",
                documents.size(), chunks.size());
        return documents.size();
    }

    private List<Document> loadDocuments() {
        List<Document> documents = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(docsPath + "*.md");

            for (Resource resource : resources) {
                try {
                    // Spring AI TextReader reads a Resource directly — no temp files needed
                    TextReader textReader = new TextReader(resource);
                    List<Document> docs = textReader.read();

                    // Add metadata to each document
                    for (Document doc : docs) {
                        doc.getMetadata().put("source", resource.getFilename());
                        doc.getMetadata().put("type", "documentation");
                    }

                    documents.addAll(docs);
                    log.info("Loaded document: {}", resource.getFilename());
                } catch (Exception e) {
                    log.error("Failed to load document: {}", resource.getFilename(), e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to resolve document resources", e);
        }
        return documents;
    }
}
