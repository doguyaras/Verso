package com.verso.connector.sharepoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.verso.connector.config.ConnectorProperties;
import com.verso.connector.confluence.ExternalEgressPolicy;
import com.verso.connector.domain.DeltaCheckpoint;
import com.verso.connector.domain.GlobSet;
import com.verso.connector.domain.SyncPlanner.ListedFile;
import com.verso.connector.smb.EgressGuard;
import com.verso.connector.smb.FileSource.Listing;
import com.verso.connector.smb.SourceException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SharePoint source against Graph in memory (ADR-0045): token acquisition (secret and signed client assertion),
 * delta paging, the deleted facet, 410 Gone, folder changes, Retry-After, the download redirect's host check, the
 * unique-permission rule, and links that leave Graph.
 */
class SharePointSourceTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final String TENANT = "11111111-2222-3333-4444-555555555555";
    private static final String CLIENT = "66666666-7777-8888-9999-000000000000";
    private static final String SECRET = "client-secret-" + UUID.randomUUID();
    private static final String HOSTS = "graph.microsoft.com,login.microsoftonline.com,*.sharepoint.com";

    private final FakeGraph graph = new FakeGraph();
    private final List<Duration> sleeps = new ArrayList<>();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    // ---------- listing ----------

    @Test
    void list_whenFull_enumeratesTheLibraryWithPathsFromTheFolderTree_andAFullCheckpoint() {
        library();
        SharePointFileSource source = source(settings(true, false, List.of(), "", List.of()));

        Listing listing = source.list(() -> { });

        assertThat(listing.complete()).isTrue();
        assertThat(listing.incremental()).isFalse();
        assertThat(paths(listing)).containsExactlyInAnyOrder(path("A"), path("B"));
        assertThat(source.documentName(file(listing, "A"))).isEqualTo("izin.pdf");
        assertThat(listing.checkpoint().full()).isTrue();
        DeltaCheckpoint.Drive drive = listing.checkpoint().drives().getFirst();
        assertThat(drive.deltaLink()).isEqualTo(FakeGraph.GRAPH + "/drives/b!drive1/root/delta?token=4");
        assertThat(drive.upserts()).containsOnlyKeys("F1");
        assertThat(drive.replaceFolders()).isTrue();
        assertThat(graph.requests).anyMatch(r -> r.contains("/root/delta?$select=") && r.contains("&$top=200"));
        assertThat(graph.requests.stream().filter(r -> r.contains("skip="))).as("followed @odata.nextLink").isNotEmpty();
        assertThat(graph.requests).as("no permission check outside the sweep").noneMatch(r -> r.endsWith("/permissions"));
        assertThat(graph.authorizations.stream().filter(a -> a != null).distinct())
                .containsExactly("Bearer " + FakeGraph.ACCESS_TOKEN);
        assertThat(graph.requests.stream().filter(r -> r.startsWith("POST "))).as("one token for the run").hasSize(1);
        Map<String, String> form = form(graph.forms.getFirst());
        assertThat(form).containsEntry("grant_type", "client_credentials").containsEntry("client_id", CLIENT)
                .containsEntry("scope", "https://graph.microsoft.com/.default").containsEntry("client_secret", SECRET);
    }

    @Test
    void list_whenAFolderOrGlobsAreSet_keepsOnlyFilesInScope() {
        library();
        assertThat(paths(source(settings(true, false, List.of(), "politikalar", List.of())).list(() -> { })))
                .as("the folder, case-insensitive like SharePoint").containsExactly(path("A"));
        assertThat(paths(source(settings(true, false, List.of(), "", List.of("*.md"))).list(() -> { })))
                .containsExactly(path("B"));
    }

    @Test
    void list_whenIncremental_listsOnlyChanges_andReportsTheDeletedFacet() {
        library();
        List<DeltaCheckpoint.Stored> stored = stored(source(settings(true, false, List.of(), "", List.of())).list(() -> { }));
        graph.change("B", "yeni notlar");
        graph.delete("A");
        graph.file("D", "yeni.txt", "F1", "yeni");

        Listing listing = source(settings(false, false, stored, "", List.of())).list(() -> { });

        assertThat(listing.incremental()).isTrue();
        assertThat(listing.complete()).isTrue();
        assertThat(paths(listing)).containsExactlyInAnyOrder(path("B"), path("D"));
        assertThat(listing.deleted()).containsExactly(path("A"));
        assertThat(listing.checkpoint().full()).isFalse();
        assertThat(listing.checkpoint().drives().getFirst().deltaLink()).endsWith("token=7");
        assertThat(graph.requests).anyMatch(r -> r.contains("/root/delta?token=4"));
    }

    @Test
    void list_whenAFileMovesOutOfTheFolder_reportsItAsDeleted() {
        library();
        List<DeltaCheckpoint.Stored> stored = stored(source(settings(true, false, List.of(), "Politikalar", List.of()))
                .list(() -> { }));
        graph.items.get("A").parent = FakeGraph.ROOT;
        graph.log.add("A");

        Listing listing = source(settings(false, false, stored, "Politikalar", List.of())).list(() -> { });

        assertThat(listing.files()).isEmpty();
        assertThat(listing.deleted()).containsExactly(path("A"));
    }

    @Test
    void list_whenTheDeltaLinkExpired_410_listsEverythingAgainWithThePermissionSweep() {
        library();
        List<DeltaCheckpoint.Stored> stored = stored(source(settings(true, false, List.of(), "", List.of())).list(() -> { }));
        graph.gone = true;
        graph.requests.clear();

        Listing listing = source(settings(false, false, stored, "", List.of())).list(() -> { });

        assertThat(listing.incremental()).as("410: a full resync").isFalse();
        assertThat(listing.complete()).isTrue();
        assertThat(paths(listing)).containsExactlyInAnyOrder(path("A"), path("B"));
        assertThat(listing.checkpoint().full()).isTrue();
        assertThat(graph.requests.stream().filter(r -> r.endsWith("/permissions"))).as("the sweep").hasSize(2);
    }

    @Test
    void list_whenAKnownFolderIsRenamedOrDeleted_listsTheLibraryFromScratch() {
        library();
        List<DeltaCheckpoint.Stored> stored = stored(source(settings(true, false, List.of(), "Politikalar", List.of()))
                .list(() -> { }));
        graph.rename("F1", "Eski");

        Listing renamed = source(settings(false, false, stored, "Politikalar", List.of())).list(() -> { });

        assertThat(renamed.incremental()).isFalse();
        assertThat(renamed.files()).as("the file left the folder with its parent").isEmpty();
        assertThat(renamed.complete()).isTrue();

        graph.delete("F1");
        Listing deleted = source(settings(false, false, stored, "Politikalar", List.of())).list(() -> { });
        assertThat(deleted.incremental()).as("children of a deleted folder are not reported one by one").isFalse();
    }

    @Test
    void list_whenThrottled_waitsForRetryAfter_andStopsWhenAskedToWaitTooLong() {
        library();
        graph.throttle = 2;
        graph.retryAfter = "7";

        Listing listing = source(settings(true, false, List.of(), "", List.of())).list(() -> { });

        assertThat(listing.complete()).isTrue();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(7), Duration.ofSeconds(7));

        graph.throttle = 1;
        graph.retryAfter = "3600";
        assertThatThrownBy(() -> source(settings(true, false, List.of(), "", List.of())).list(() -> { }))
                .isInstanceOfSatisfying(SourceException.class, e -> assertThat(e.isFatal()).isTrue())
                .hasMessage("RATE_LIMITED");
    }

    @Test
    void list_whenANextLinkLeavesGraph_isIncompleteAndKeepsNoCheckpoint() {
        library();
        graph.nextLinkOverride = "https://graph.microsoft.com.evil.example/v1.0/drives/b!drive1/root/delta?skip=2";

        Listing listing = source(settings(true, false, List.of(), "", List.of())).list(() -> { });

        assertThat(listing.complete()).isFalse();
        assertThat(listing.problem()).isEqualTo("LISTING_LINK_REFUSED");
        assertThat(listing.checkpoint()).isNull();
        assertThat(graph.requests).noneMatch(r -> r.contains("evil"));
    }

    // ---------- download ----------

    @Test
    void read_whenGraphRedirectsToSharePoint_downloadsWithoutTheToken() {
        library();
        SharePointFileSource source = source(settings(true, false, List.of(), "", List.of()));
        source.list(() -> { });
        graph.requests.clear();
        graph.authorizations.clear();

        byte[] content = source.read(path("B"), 1_000);

        assertThat(new String(content, StandardCharsets.UTF_8)).isEqualTo("notlar");
        assertThat(graph.requests.getLast()).startsWith("https://contoso.sharepoint.com/download/B");
        assertThat(graph.authorizations.getLast()).as("the pre-authenticated URL gets no token").isNull();
        assertThatThrownBy(() -> source.read(path("B"), 3)).hasMessage("TOO_LARGE");
        assertThatThrownBy(() -> source.read("drives/b!drive1/items/../x", 3)).hasMessage("NOT_FOUND");
    }

    @Test
    void read_whenGraphRedirectsToAHostOutsideTheAllowlist_refusesWithoutRequestingIt() {
        library();
        graph.downloadHost = "https://files.evil.example";
        SharePointFileSource source = source(settings(true, false, List.of(), "", List.of()));
        source.list(() -> { });

        assertThatThrownBy(() -> source.read(path("B"), 1_000))
                .isInstanceOfSatisfying(SourceException.class, e -> assertThat(e.isFatal()).isFalse())
                .hasMessage("REDIRECT_REFUSED");
        assertThat(graph.requests).noneMatch(r -> r.startsWith("https://files.evil.example"));
    }

    @Test
    void egress_whenLocalModeOffOrHostNotSharePoint_refuses_andDownloadsNeedHttpsOnAllowedHosts() {
        SharePointEgress cloud = egress(true, "cloud");
        assertThat(cloud.allowsSite("contoso.sharepoint.com")).isTrue();
        assertThat(cloud.allowsSite("contoso.example.com")).isFalse();
        assertThat(cloud.allowsSite("10.0.0.5")).isFalse();
        assertThat(egress(true, "local").allowsSite("contoso.sharepoint.com")).as("strict local mode").isFalse();
        assertThat(egress(false, "cloud").allowsSite("contoso.sharepoint.com")).as("external egress off").isFalse();
        assertThat(cloud.downloadAllowed(URI.create("https://contoso.sharepoint.com/x"))).isTrue();
        assertThat(cloud.downloadAllowed(URI.create("http://contoso.sharepoint.com/x"))).isFalse();
        assertThat(cloud.downloadAllowed(URI.create("https://u:p@contoso.sharepoint.com/x"))).isFalse();
        assertThat(cloud.downloadAllowed(URI.create("https://contoso.sharepoint.com:8443/x"))).isFalse();
        assertThat(cloud.downloadAllowed(URI.create("https://sharepoint.com.evil.example/x"))).isFalse();
        assertThat(cloud.downloadAllowed(URI.create("https://graph.microsoft.com/x"))).as("not a download host").isFalse();
        assertThat(cloud.graphLink("https://graph.microsoft.com/v1.0/drives/x/root/delta?token=1")).isNotNull();
        assertThat(cloud.graphLink("https://graph.microsoft.com/v1.0/me/messages")).isNull();
        assertThat(cloud.graphLink("https://graph.microsoft.com/v1.0/drives/%2e%2e/x")).isNull();
        assertThat(cloud.graphLink("http://graph.microsoft.com/v1.0/drives/x")).isNull();
    }

    // ---------- permissions (v1 safety rule) ----------

    @Test
    void uniquePermissions_whenSwept_markTheFileRestricted_andAFetchChecksThemToo() {
        library();
        graph.unique("A");

        Listing swept = source(settings(true, true, List.of(), "", List.of())).list(() -> { });
        assertThat(file(swept, "A").restricted()).isTrue();
        assertThat(file(swept, "B").restricted()).isFalse();

        SharePointFileSource plain = source(settings(true, false, List.of(), "", List.of()));
        Listing listing = plain.list(() -> { });
        assertThat(file(listing, "A").restricted()).as("outside the sweep, checked at fetch").isFalse();
        assertThatThrownBy(() -> plain.read(path("A"), 1_000)).hasMessage("RESTRICTED");
        assertThat(graph.requests).noneMatch(r -> r.contains("/download/A"));

        graph.items.get("B").permissions = "[]";
        assertThatThrownBy(() -> plain.read(path("B"), 1_000)).as("fail closed")
                .isInstanceOfSatisfying(SourceException.class, e -> assertThat(e.isFatal()).isFalse())
                .hasMessage("RESTRICTION_CHECK_FAILED");
        Listing unchecked = source(settings(true, true, List.of(), "", List.of())).list(() -> { });
        assertThat(unchecked.complete()).isFalse();
        assertThat(unchecked.problem()).isEqualTo("RESTRICTION_CHECK_FAILED");
        assertThat(paths(unchecked)).doesNotContain(path("B"));
    }

    @Test
    void permissionRule_whenInheritedOnlyFromTheLibraryRoot_isNotUnique() {
        JsonMapper json = JsonMapper.builder().build();
        assertThat(PermissionRule.unique(json.readTree("[{\"inheritedFrom\":{\"id\":\"R\"}}]"), "R")).isFalse();
        assertThat(PermissionRule.unique(json.readTree("[{\"inheritedFrom\":{\"path\":\"/drives/b!x/root:\"}}]"), "R")).isFalse();
        assertThat(PermissionRule.unique(json.readTree("[{\"inheritedFrom\":{\"id\":\"FOLDER\"}}]"), "R"))
                .as("a folder below the root broke inheritance").isTrue();
        assertThat(PermissionRule.unique(json.readTree("[{\"inheritedFrom\":{\"id\":\"R\"}},{\"link\":{\"scope\":\"anonymous\"}}]"),
                "R")).as("a sharing link on the file").isTrue();
        assertThat(PermissionRule.unique(json.readTree("[]"), "R")).as("unknown, never inherited").isNull();
        assertThat(PermissionRule.unique(json.readTree("{}"), "R")).isNull();
    }

    // ---------- tokens ----------

    @Test
    void token_whenTheCertificateIsUsed_sendsASignedClientAssertion() throws Exception {
        TestCertificates.Generated cert = TestCertificates.generate(null, 2);
        library();
        EntraTokens.Credential credential = new EntraTokens.Assertion(
                ClientAssertion.parse(cert.pem().toCharArray(), Instant.now()), "RS256");
        SharePointFileSource source = new SharePointFileSource(settings(true, false, List.of(), "", List.of()),
                client(egress(true, "cloud"), credential), 200, 100_000, 32);

        source.list(() -> { });

        Map<String, String> form = form(graph.forms.getFirst());
        assertThat(form).doesNotContainKey("client_secret")
                .containsEntry("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        SignedJWT jwt = SignedJWT.parse(form.get("client_assertion"));
        assertThat(jwt.verify(new RSASSAVerifier((RSAPublicKey) cert.certificate().getPublicKey()))).isTrue();
        assertThat(jwt.getHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(jwt.getHeader().getX509CertSHA256Thumbprint()).isNotNull();
        assertThat(jwt.getJWTClaimsSet().getAudience())
                .containsExactly("https://login.microsoftonline.com/" + TENANT + "/oauth2/v2.0/token");
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo(CLIENT);
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo(CLIENT);
        assertThat(jwt.getJWTClaimsSet().getJWTID()).isNotBlank();
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(credential.toString()).doesNotContain("PRIVATE");
    }

    @Test
    void certificate_whenTheKeyDoesNotMatchOrItExpired_isRefused() throws Exception {
        TestCertificates.Generated one = TestCertificates.generate(null, 2);
        TestCertificates.Generated two = TestCertificates.generate(null, 2);
        String mixed = one.pem().substring(0, one.pem().indexOf("-----BEGIN CERTIFICATE-----"))
                + two.pem().substring(two.pem().indexOf("-----BEGIN CERTIFICATE-----"));
        assertThatThrownBy(() -> ClientAssertion.parse(mixed.toCharArray(), Instant.now())).hasMessage("CREDENTIAL_INVALID");
        assertThatThrownBy(() -> ClientAssertion.parse("not a pem".toCharArray(), Instant.now())).hasMessage("CREDENTIAL_INVALID");
        assertThatThrownBy(() -> ClientAssertion.parse(one.pem().toCharArray(), Instant.now().plus(Duration.ofDays(30))))
                .hasMessage("CREDENTIAL_EXPIRED");
    }

    @Test
    void token_whenGraphSays401_refreshesOnce_thenStops_andARefusedCredentialStopsTheRun() {
        library();
        graph.unauthorized = 1;
        assertThat(source(settings(true, false, List.of(), "", List.of())).list(() -> { }).complete()).isTrue();
        assertThat(graph.forms).as("a new token after the 401").hasSize(2);

        graph.unauthorized = 2;
        assertThatThrownBy(() -> source(settings(true, false, List.of(), "", List.of())).list(() -> { }))
                .hasMessage("SOURCE_AUTH_FAILED");

        graph.unauthorized = 0;
        graph.tokenStatus = 401;
        assertThatThrownBy(() -> source(settings(true, false, List.of(), "", List.of())).list(() -> { }))
                .hasMessage("SOURCE_AUTH_FAILED");
        assertThat(new EntraTokens.Secret(SECRET.toCharArray()).toString()).doesNotContain(SECRET);
    }

    // ---------- helpers ----------

    /** F1 "Politikalar" with A izin.pdf; B notlar.md and C resim.png at the root. */
    private void library() {
        graph.folder("F1", "Politikalar", FakeGraph.ROOT);
        graph.file("A", "izin.pdf", "F1", "izin 14 gun");
        graph.file("B", "notlar.md", FakeGraph.ROOT, "notlar");
        graph.file("C", "resim.png", FakeGraph.ROOT, "png");
    }

    private SharePointFileSource source(SharePointRunSettings settings) {
        return new SharePointFileSource(settings, client(egress(true, "cloud"),
                new EntraTokens.Secret(SECRET.toCharArray())), 200, 100_000, 32);
    }

    private GraphClient client(SharePointEgress egress, EntraTokens.Credential credential) {
        ConnectorProperties.SharePoint sp = properties(true).sharepoint();
        EntraTokens tokens = new EntraTokens(EntraTokens.endpoint(URI.create(ConnectorProperties.AUTHORITY), TENANT),
                CLIENT, "https://graph.microsoft.com/.default", credential, graph, sp, clock, sleeps::add);
        return new GraphClient(egress, tokens, graph, sp, clock, sleeps::add);
    }

    private SharePointEgress egress(boolean enabled, String mode) {
        ConnectorProperties properties = properties(enabled);
        return new SharePointEgress(properties,
                new ExternalEgressPolicy(properties, new MockEnvironment().withProperty("verso.ai.mode", mode)),
                new EgressGuard(properties));
    }

    private static ConnectorProperties properties(boolean externalEgress) {
        return new ConnectorProperties(true, Duration.ofSeconds(30), Duration.ofMinutes(10), "10.0.0.0/8", 50, 500,
                DataSize.ofGigabytes(2), 100_000, 32, Duration.ofMinutes(5), Duration.ofHours(24), Duration.ofMinutes(1),
                Duration.ofMinutes(2), 20, 20, 3, 100, null, null,
                new ConnectorProperties.ExternalEgress(externalEgress, externalEgress ? HOSTS : ""), null);
    }

    private static SharePointRunSettings settings(boolean full, boolean sweep, List<DeltaCheckpoint.Stored> stored,
                                                  String prefix, List<String> include) {
        return new SharePointRunSettings(UUID.randomUUID(), "https://contoso.sharepoint.com/sites/hr", null, List.of(),
                prefix, GlobSet.compile(include), GlobSet.compile(List.of()), true, TENANT, CLIENT, "CLIENT_SECRET",
                "SECRET_CONNECTOR_SHAREPOINT", full, sweep, stored);
    }

    private static List<DeltaCheckpoint.Stored> stored(Listing listing) {
        return listing.checkpoint().drives().stream()
                .map(d -> new DeltaCheckpoint.Stored(d.driveId(), d.rootId(), d.deltaLink(), d.upserts())).toList();
    }

    private static String path(String id) {
        return "drives/b!drive1/items/" + id;
    }

    private static List<String> paths(Listing listing) {
        return listing.files().stream().map(ListedFile::path).toList();
    }

    private static ListedFile file(Listing listing, String id) {
        return listing.files().stream().filter(f -> f.path().equals(path(id))).findFirst().orElseThrow();
    }

    private static Map<String, String> form(String body) {
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            form.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return form;
    }
}
