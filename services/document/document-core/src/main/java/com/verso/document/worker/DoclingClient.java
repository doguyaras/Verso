package com.verso.document.worker;

import com.verso.document.config.DoclingProperties;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP client of the docling-serve sidecar (ADR-0032): submit one file, poll its task, fetch the DoclingDocument JSON.
 * Asynchronous API, because an OCR'd document takes longer than any sane read timeout; every call has its own read
 * timeout, the whole conversion the document timeout. The connection may only go to an internal address (the
 * sidecar's network has no gateway; this is the second fence, llm-rules 1.1), never follows a redirect, and reads at
 * most max-response-size bytes. The file goes out under a fixed name: the user's file name never leaves the JVM
 * (llm-rules 2.1). Failures carry a fixed kind, never the sidecar's message or the document's text.
 */
@Component
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DoclingClient {

    /** Why a conversion produced nothing usable; none of them is the document's fault on its own. */
    enum Failure { UNAVAILABLE, TIMEOUT, CONVERSION_FAILED, INVALID_RESPONSE, TOO_LARGE, CANCELLED }

    /** A conversion that did not succeed; no cause and no message: both could carry document text. */
    static final class DoclingException extends RuntimeException {
        private final Failure failure;

        DoclingException(Failure failure) {
            super(failure.name(), null, false, false);
            this.failure = failure;
        }

        Failure failure() {
            return failure;
        }
    }

    /** docling-serve task ids are UUIDs; anything else is not put into a URL. */
    private static final Pattern TASK_ID = Pattern.compile("[A-Za-z0-9-]{1,64}");
    /** DoclingDocument JSON nests a few levels per group; deeper input is not a document. */
    private static final int MAX_JSON_DEPTH = 200;

    private final DoclingProperties properties;
    private final RestClient client;
    private final JsonMapper json;

    public DoclingClient(DoclingProperties properties, ObjectProvider<RestClient.Builder> builders,
                         ObjectProvider<HttpClientSettings> settings) {
        this.properties = properties;
        this.json = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(MAX_JSON_DEPTH)
                .maxDocumentLength(properties.maxResponseSize().toBytes())
                .build()).build()).build();
        if (!properties.enabled()) {
            this.client = null;
            return;
        }
        HttpClientSettings base = settings.getIfAvailable(HttpClientSettings::defaults);
        HttpClientSettings own = base
                .withTimeouts(properties.connectTimeout(), properties.readTimeout())
                .withRedirects(HttpRedirects.DONT_FOLLOW)
                .withInetAddressFilter(InetAddressFilter.internalAddresses());
        RestClient.Builder builder = builders.getIfAvailable(RestClient::builder);
        this.client = builder
                .baseUrl(properties.baseUrl().toString())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(own))
                .build();
    }

    /** The configured HTTP client, for the egress tests (llm-rules 1.1). */
    RestClient restClient() {
        return client;
    }

    /**
     * The DoclingDocument of one file. {@code stopping} is checked between polls: a shutdown does not wait for the
     * sidecar. The task is not cancelled on the sidecar (docling-serve 1.38 has no cancel call); its own
     * document_timeout stops it.
     */
    JsonNode convert(byte[] file, String format, BooleanSupplier stopping) {
        if (client == null) throw new IllegalStateException("docling is disabled");
        long deadline = System.nanoTime() + properties.documentTimeout().toNanos();
        String taskId = submit(file, format);
        while (true) {
            String status = status(taskId);
            if ("success".equals(status)) break;
            if ("failure".equals(status)) throw new DoclingException(Failure.CONVERSION_FAILED);
            if (stopping.getAsBoolean()) throw new DoclingException(Failure.CANCELLED);
            if (System.nanoTime() + properties.pollInterval().toNanos() > deadline) {
                throw new DoclingException(Failure.TIMEOUT);
            }
            try {
                Thread.sleep(properties.pollInterval());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DoclingException(Failure.CANCELLED);
            }
        }
        JsonNode result = result(taskId);
        if (!"success".equals(result.path("status").asString(""))) {
            throw new DoclingException(Failure.CONVERSION_FAILED);
        }
        JsonNode document = result.path("document").path("json_content");
        if (!document.isObject()) throw new DoclingException(Failure.INVALID_RESPONSE);
        return document;
    }

    private String submit(byte[] file, String format) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("files", new ByteArrayResource(file) {
            @Override
            public String getFilename() {
                return "document." + format;
            }
        });
        // Only the input format the upload was detected as: the sidecar does not sniff the bytes into another parser.
        form.add("from_formats", format);
        form.add("to_formats", "json");
        form.add("target_type", "inbody");
        // OCR only where there is no text layer (bitmap areas above docling's 5 % threshold), Tesseract with the
        // installed tur + eng models; no images, page images, picture models or enrichment: text and tables only.
        form.add("do_ocr", "true");
        form.add("force_ocr", "false");
        form.add("ocr_preset", "tesserocr");
        properties.ocrLanguages().forEach(language -> form.add("ocr_lang", language));
        form.add("do_table_structure", "true");
        form.add("table_mode", "accurate");
        form.add("include_images", "false");
        form.add("include_page_images", "false");
        form.add("image_export_mode", "placeholder");
        form.add("do_picture_classification", "false");
        form.add("do_picture_description", "false");
        form.add("do_code_enrichment", "false");
        form.add("do_formula_enrichment", "false");
        form.add("do_chart_extraction", "false");
        form.add("abort_on_error", "true");
        form.add("document_timeout", Long.toString(properties.documentTimeout().toSeconds()));
        JsonNode task = call(() -> client.post().uri("/v1/convert/file/async")
                .contentType(MediaType.MULTIPART_FORM_DATA).body(form)
                .exchange((request, response) -> read(response.getStatusCode().value(), response.getBody())));
        String taskId = task.path("task_id").asString("");
        if (!TASK_ID.matcher(taskId).matches()) throw new DoclingException(Failure.INVALID_RESPONSE);
        return taskId;
    }

    private String status(String taskId) {
        JsonNode status = call(() -> client.get().uri("/v1/status/poll/{id}", taskId)
                .exchange((request, response) -> read(response.getStatusCode().value(), response.getBody())));
        return status.path("task_status").asString("");
    }

    private JsonNode result(String taskId) {
        return call(() -> client.get().uri("/v1/result/{id}", taskId)
                .exchange((request, response) -> read(response.getStatusCode().value(), response.getBody())));
    }

    private JsonNode read(int status, InputStream body) throws IOException {
        if (status >= 500) throw new DoclingException(Failure.UNAVAILABLE);
        if (status != 200) throw new DoclingException(Failure.INVALID_RESPONSE);
        byte[] bytes = readLimited(body, properties.maxResponseSize().toBytes());
        try {
            return json.readTree(bytes);
        } catch (JacksonException e) {
            throw new DoclingException(Failure.INVALID_RESPONSE);
        }
    }

    /** Reads at most {@code max} bytes; a longer body is refused before it fills the heap. */
    static byte[] readLimited(InputStream in, long max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > max) throw new DoclingException(Failure.TOO_LARGE);
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private interface Call {
        JsonNode run();
    }

    /** Maps transport failures to a fixed kind; a read timeout surfaces as an I/O error like a refused connection. */
    private static JsonNode call(Call call) {
        try {
            JsonNode node = call.run();
            if (node == null) throw new DoclingException(Failure.INVALID_RESPONSE);
            return node;
        } catch (RuntimeException e) {
            // The client may wrap what the response handler threw; the handler's own kind wins.
            Throwable cause = e;
            for (int i = 0; i < 20 && cause != null; i++, cause = cause.getCause()) {
                if (cause instanceof DoclingException own) throw own;
                if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                    throw new DoclingException(Failure.TIMEOUT);
                }
            }
            // Refused or reset connection, unknown host, an address the filter refuses (FilteredHostException).
            throw new DoclingException(Failure.UNAVAILABLE);
        }
    }
}
