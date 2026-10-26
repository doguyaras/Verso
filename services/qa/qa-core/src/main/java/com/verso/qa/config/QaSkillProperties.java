package com.verso.qa.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Skills (procedures) of the learning layer (ADR-0053). Starting values (reference 1.4), measured per customer later.
 * The approval rules themselves (2-5 golden cases, every step grounded, every source readable, the two-person rule of
 * {@code verso.qa.knowledge.two-person-rule}) are not settings.
 *
 * @param servingEnabled      show a matching APPROVED skill above the answer ("Bu bir prosedür"); off by default until
 *                            the match threshold is measured. Drafting, approval, export and MCP do not depend on it
 * @param matchThreshold      cosine similarity of the question to a skill's nearest trigger, at or above which the skill
 *                            is shown; conservative
 * @param matchCandidates     nearest APPROVED skills checked per question (each needs the caller's access to all its
 *                            sources)
 * @param clusterCardThreshold how near a VERIFIED card's question must be to a cluster's centre to be drafted from it
 *                            (the diagnostics' own cluster threshold by default)
 * @param stepMaxChars        a template step is cut to this many characters
 * @param condenseTimeout     the optional local-model condensing call; on timeout the template text stays
 * @param archivedRetention   archived skills are deleted this long after their last change
 */
@ConfigurationProperties("verso.qa.skills")
public record QaSkillProperties(
        @DefaultValue("false") boolean servingEnabled,
        @DefaultValue("0.90") double matchThreshold,
        @DefaultValue("3") int matchCandidates,
        @DefaultValue("0.80") double clusterCardThreshold,
        @DefaultValue("400") int stepMaxChars,
        @DefaultValue("60s") Duration condenseTimeout,
        @DefaultValue("365d") Duration archivedRetention) {

    public QaSkillProperties {
        if (matchThreshold < 0.80 || matchThreshold > 1) {
            throw new IllegalStateException("verso.qa.skills.match-threshold must be 0.80..1");
        }
        if (matchCandidates < 1 || matchCandidates > 10) {
            throw new IllegalStateException("verso.qa.skills.match-candidates must be 1..10");
        }
        if (clusterCardThreshold < 0.5 || clusterCardThreshold > 1) {
            throw new IllegalStateException("verso.qa.skills.cluster-card-threshold must be 0.5..1");
        }
        if (stepMaxChars < 80 || stepMaxChars > 1000) {
            throw new IllegalStateException("verso.qa.skills.step-max-chars must be 80..1000");
        }
        if (!condenseTimeout.isPositive() || condenseTimeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalStateException("verso.qa.skills.condense-timeout must be 1ms..5m");
        }
        if (archivedRetention.compareTo(Duration.ofDays(30)) < 0) {
            throw new IllegalStateException("verso.qa.skills.archived-retention must be at least 30d");
        }
    }
}
