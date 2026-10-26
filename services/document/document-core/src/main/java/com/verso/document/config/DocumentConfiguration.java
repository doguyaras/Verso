package com.verso.document.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.unit.DataSize;

/** Document module wiring: properties, the scheduled ingestion poll (ADR-0011) and the optional sidecar (ADR-0032). */
@Configuration(proxyBeanMethods = false)
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties({DocumentProperties.class, CollectionProperties.class, DoclingProperties.class,
        RerankerProperties.class, SystemModelProperties.class})
@EnableScheduling
public class DocumentConfiguration {


    /**
     * The servlet multipart limit rejects a too large request before the controller (413); the module limit must not
     * be larger, or the configured value would be a promise the server cannot keep. A sidecar conversion that outlived
     * the claim lease would let a second worker claim the same document (ADR-0032).
     */
    public DocumentConfiguration(DocumentProperties properties, DoclingProperties docling,
                                 @Value("${spring.servlet.multipart.max-file-size:1MB}") DataSize multipartLimit) {
        if (properties.maxFileSize().toBytes() > multipartLimit.toBytes()) {
            throw new IllegalStateException(
                    "verso.document.max-file-size must not exceed spring.servlet.multipart.max-file-size");
        }
        if (docling.enabled() && docling.documentTimeout().compareTo(properties.ingestion().lease()) >= 0) {
            throw new IllegalStateException(
                    "verso.document.docling.document-timeout must be shorter than verso.document.ingestion.lease");
        }
    }
}
