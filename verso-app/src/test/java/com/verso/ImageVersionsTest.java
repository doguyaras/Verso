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

        assertThat(images).as("image references found").hasSizeGreaterThanOrEqualTo(5);
        for (String image : images) {
            if (image.startsWith("*") || image.startsWith("verso:")) continue; // YAML alias, our own build
            assertThat(image).as(image).containsPattern(DIGEST);
        }
    }

    @Test
    void postgresImage_whenUsedByTestsAndCompose_isTheSame() throws IOException {
        // Full reference, digest included: a tag alone could be re-pointed between test and deploy (review S6).
        Matcher anchor = Pattern.compile("x-postgres-image: &postgres-image (\\S+@sha256:[0-9a-f]{64})")
                .matcher(read("compose.yaml"));
        assertThat(anchor.find()).as("compose postgres image anchor").isTrue();
        assertThat(VersoPostgres.IMAGE).isEqualTo(anchor.group(1));
    }

    private static String read(String file) throws IOException {
        return Files.readString(ROOT.resolve(file), StandardCharsets.UTF_8);
    }
}
