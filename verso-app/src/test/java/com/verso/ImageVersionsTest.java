package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.support.VersoPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Container images (reference 18.1): every image reference is pinned by digest (a tag is a movable pointer), and the
 * PostgreSQL that tests run against is the one compose deploys. Without this, Testcontainers could quietly test a
 * different PostgreSQL or pgvector than production.
 */
class ImageVersionsTest {

    private static final Path ROOT = VersoPostgres.repoRoot();
    private static final Pattern COMPOSE_IMAGE = Pattern.compile("(?m)^\\s*(?:image:|x-postgres-image: &postgres-image)\\s*([^\\s#]+)");
    private static final Pattern DOCKERFILE_FROM = Pattern.compile("(?m)^FROM\\s+(\\S+)");
    private static final Pattern DIGEST = Pattern.compile("@sha256:[0-9a-f]{64}$");

    @Test
    void images_whenReferencedInComposeOrDockerfile_arePinnedByDigest() throws IOException {
        List<String> images = new ArrayList<>();
        Matcher compose = COMPOSE_IMAGE.matcher(read("compose.yaml"));
        while (compose.find()) images.add(compose.group(1));
        Matcher from = DOCKERFILE_FROM.matcher(read("Dockerfile"));
        while (from.find()) images.add(from.group(1));
        // The docling sidecar (ADR-0032): its overlay builds our image on a digest-pinned upstream image.
        Matcher overlay = COMPOSE_IMAGE.matcher(read("deploy/compose.docling.yaml"));
        while (overlay.find()) images.add(overlay.group(1));
        Matcher docling = DOCKERFILE_FROM.matcher(read("deploy/docling/Dockerfile"));
        assertThat(docling.find()).as("deploy/docling/Dockerfile FROM").isTrue();
        images.add(docling.group(1));

        assertThat(images).as("image references found").hasSizeGreaterThanOrEqualTo(5);
        for (String image : images) {
            // YAML alias, our own builds
            if (image.startsWith("*") || image.startsWith("verso:") || image.startsWith("verso-docling:")) continue;
            assertThat(image).as(image).containsPattern(DIGEST);
        }
    }

    /**
     * ADR-0032: the sidecar runs docling 2.142.1 or later (the DoS fixes of 23 Oct 2026; docling-serve 1.38.1 ships
     * it), and the Turkish OCR model it adds is pinned by checksum, not fetched at runtime.
     */
    @Test
    void doclingSidecar_isAVersionWithTheDosFixes_andItsOcrModelIsPinned() throws IOException {
        String dockerfile = read("deploy/docling/Dockerfile");
        Matcher version = Pattern.compile("docling-serve-cpu:v(\\d+)\\.(\\d+)\\.(\\d+)@").matcher(dockerfile);
        assertThat(version.find()).isTrue();
        int[] v = {Integer.parseInt(version.group(1)), Integer.parseInt(version.group(2)), Integer.parseInt(version.group(3))};
        assertThat(v[0] > 1 || (v[0] == 1 && (v[1] > 38 || (v[1] == 38 && v[2] >= 1))))
                .as("docling-serve >= 1.38.1 (docling 2.142.1)").isTrue();
        assertThat(dockerfile).containsPattern("ADD --checksum=sha256:[0-9a-f]{64} .*\\\\\\R\\s+https://raw\\.githubusercontent\\.com/tesseract-ocr/tessdata_best/[0-9a-f]{40}/tur\\.traineddata");
    }

    @Test
    void postgresImage_whenUsedByTestsAndCompose_isTheSame() throws IOException {
        // Full reference, digest included: a tag alone could be re-pointed between test and deploy (review S6).
        Matcher anchor = Pattern.compile("x-postgres-image: &postgres-image (\\S+@sha256:[0-9a-f]{64})")
                .matcher(read("compose.yaml"));
        assertThat(anchor.find()).as("compose postgres image anchor").isTrue();
        assertThat(VersoPostgres.IMAGE).isEqualTo(anchor.group(1));
    }

    @Test
    void flywayCli_whenUsedByRestoreDrill_hasTheLibraryVersion() throws IOException {
        // The drill validates the history the library wrote; both move together (ADR-0009, docs/versions.md).
        Matcher library = Pattern.compile("<flyway.version>([^<]+)</flyway.version>").matcher(read("pom.xml"));
        assertThat(library.find()).as("flyway.version in pom.xml").isTrue();
        Matcher cli = Pattern.compile("image:\\s*flyway/flyway:([^@\\s]+)@sha256:[0-9a-f]{64}").matcher(read("compose.yaml"));
        int found = 0;
        while (cli.find()) {
            assertThat(cli.group(1)).as("Flyway CLI image tag").isEqualTo(library.group(1));
            found++;
        }
        assertThat(found).as("Flyway CLI images in compose.yaml").isGreaterThanOrEqualTo(1);
    }

    private static String read(String file) throws IOException {
        return Files.readString(ROOT.resolve(file), StandardCharsets.UTF_8);
    }
}
