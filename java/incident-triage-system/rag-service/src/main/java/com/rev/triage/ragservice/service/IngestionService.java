package com.rev.triage.ragservice.service;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.loader.FileSystemDocumentLoader;
import dev.langchain4j.data.document.parser.TextDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;

    @Value("${rag.docs.path}")
    private String docsPath;

    public IngestionService(EmbeddingStore<TextSegment> embeddingStore,
                            EmbeddingModel embeddingModel) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
    }

    public int ingestDocuments() {
        log.info("Starting document ingestion from {}", docsPath);

        List<Document> documents = loadDocuments();
        if (documents.isEmpty()) {
            log.warn("No documents found to ingest");
            return 0;
        }

        log.info("Loaded {} documents, starting chunking and embedding...", documents.size());

        EmbeddingStoreIngestor ingestor = EmbeddingStoreIngestor.builder()
                .documentSplitter(DocumentSplitters.recursive(1000, 200))
                .embeddingModel(embeddingModel)
                .embeddingStore(embeddingStore)
                .build();

        ingestor.ingest(documents);

        log.info("Successfully ingested {} documents into ChromaDB", documents.size());
        return documents.size();
    }

    private List<Document> loadDocuments() {
        List<Document> documents = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(docsPath + "*.md");

            for (Resource resource : resources) {
                try {
                    Path tempFile = Files.createTempFile("doc-", ".md");
                    Files.copy(resource.getInputStream(), tempFile,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);

                    Document doc = FileSystemDocumentLoader.loadDocument(
                            tempFile, new TextDocumentParser());
                    doc.metadata().put("source", resource.getFilename());
                    documents.add(doc);

                    log.info("Loaded document: {}", resource.getFilename());
                    Files.deleteIfExists(tempFile);
                } catch (IOException e) {
                    log.error("Failed to load document: {}", resource.getFilename(), e);
                }
            }
        } catch (IOException e) {
            log.error("Failed to resolve document resources", e);
        }
        return documents;
    }
}
