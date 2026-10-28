package com.verso.qa.controller;

import com.verso.platform.core.trust.TrustContributor;
import com.verso.platform.core.trust.TrustReport;
import com.verso.platform.security.authority.VersoRoles;
import com.verso.platform.security.jwt.TrustedIssuer;
import com.verso.platform.security.jwt.TrustedIssuers;
import com.verso.qa.api.dto.TrustResponse;
import com.verso.qa.config.AiMode;
import com.verso.qa.config.VersoAiProperties;
import com.verso.qa.service.gateway.ModelGatewayAdmin;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/admin/trust (ADR-0050): the facts the running application knows about where data can go, for {@code verso-admin}
 * only (ADR-0019; everyone else gets 403 ACCESS_DENIED). Read-only and not cached. Every value is configuration an
 * admin already set or a count; nothing is a secret, a secret file name, a connector's address or any content.
 * Modules add their own facts through {@link TrustContributor}; this class reads no module's data.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/admin/trust")
public class AdminTrustController {

    static final String GUARANTEE_LOCAL = "Yerel mod: soru, belge ve cevaplar bu sunucudan çıkmaz. Uygulama internete "
            + "çıkamaz; bağlayıcıların dış bağlantısı kapalıdır ve açılırsa uygulama başlamaz.";
    static final String GUARANTEE_CLOUD = "Bulut modu: soru ve seçilen en çok 5 pasaj model sağlayıcısına gider. "
            + "Belgelerin kendisi ve embedding bu sunucuda kalır.";
    static final String BACKUP_VERIFICATION = "kurulum betiğiyle doğrulanır";

    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern WORD = Pattern.compile("[a-z][a-z0-9-]{0,31}");
    private static final CacheControl NO_STORE = CacheControl.noStore().cachePrivate();

    private final VersoAiProperties ai;
    private final Environment environment;
    private final TrustedIssuers issuers;
    private final ObjectProvider<BuildProperties> build;
    private final List<TrustContributor> contributors;
    private final Clock clock;
    private final ModelGatewayAdmin modelConnections;

    public AdminTrustController(VersoAiProperties ai, Environment environment, TrustedIssuers issuers,
                                ObjectProvider<BuildProperties> build, List<TrustContributor> contributors, Clock clock,
                                ModelGatewayAdmin modelConnections) {
        this.modelConnections = modelConnections;
        this.ai = ai;
        this.environment = environment;
        this.issuers = issuers;
        this.build = build;
        this.contributors = List.copyOf(contributors);
        this.clock = clock;
    }

    @GetMapping
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public ResponseEntity<TrustResponse> trust() {
        TrustReport report = new TrustReport();
        contributors.forEach(c -> c.contribute(report));
        Instant now = clock.instant();
        boolean mcpEnabled = environment.getProperty("verso.mcp.enabled", Boolean.class, false);
        BuildProperties info = build.getIfAvailable();
        return ResponseEntity.ok().cacheControl(NO_STORE).body(new TrustResponse(now,
                new TrustResponse.Ai(ai.mode().header(), word(environment.getProperty("spring.ai.model.chat")),
                        ai.chatModel(), ai.embeddingModel()),
                new TrustResponse.Egress(report.externalConnectorEgress(), report.egressHosts()),
                new TrustResponse.Connectors(report.connectorTypes(), report.cloudConnectorPresent(),
                        report.connectorsKnown()),
                new TrustResponse.Mcp(mcpEnabled, mcpEnabled ? word(environment.getProperty("verso.mcp.audience")) : null),
                new TrustResponse.Identity(issuers.all().stream()
                        .map(i -> new TrustResponse.Identity.Issuer(i.name(), hostOf(i))).toList()),
                new TrustResponse.Build(info == null ? null : info.getVersion(), info == null ? null : info.getTime(),
                        digest(environment.getProperty("VERSO_IMAGE_DIGEST"))),
                new TrustResponse.Audit(report.lastAuditEventAt(), ageSeconds(report.oldestOutboxAt(), now)),
                new TrustResponse.Backup(null, BACKUP_VERIFICATION),
                ai.mode() == AiMode.CLOUD ? GUARANTEE_CLOUD : GUARANTEE_LOCAL, models()));
    }

    /**
     * The enabled gateway connections (ADR-0056): where a model call may send data. Host and region only; never the
     * base URL's path, a key or its hint. An unreadable list is "unknown", never "none".
     */
    private TrustResponse.Models models() {
        try {
            return new TrustResponse.Models(true, modelConnections.list().stream()
                    .filter(com.verso.qa.api.dto.ModelConnectionResponse::enabled)
                    .map(c -> new TrustResponse.Models.Connection(c.name(), c.type().name(), c.locality(),
                            c.egressHost(), c.region())).toList());
        } catch (RuntimeException e) {
            return new TrustResponse.Models(false, List.of());
        }
    }

    /** The issuer's host only: the path, the key address and the client are not the customer's business here. */
    static String hostOf(TrustedIssuer issuer) {
        try {
            return URI.create(issuer.issuer()).getHost();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static Long ageSeconds(Instant since, Instant now) {
        return since == null ? null : Math.max(0, Duration.between(since, now).toSeconds());
    }

    /** Only a well-formed digest is passed on: the value comes from an environment variable. */
    static String digest(String value) {
        return value != null && DIGEST.matcher(value.strip()).matches() ? value.strip() : null;
    }

    private static String word(String value) {
        return value != null && WORD.matcher(value.strip()).matches() ? value.strip() : null;
    }
}
