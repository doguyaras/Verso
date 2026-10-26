package com.verso.qa.service;

import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SourcePage;
import com.verso.qa.api.dto.KnowledgeCardState;
import com.verso.qa.api.dto.KnowledgeStaleReason;
import com.verso.qa.repository.KnowledgeCardRepository.Binding;
import com.verso.qa.repository.KnowledgeCardRepository.Candidate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The rules of the answer memory (ADR-0049) without I/O: the state machine, the hash binding, the scope, the matching
 * threshold and the dispute threshold. Promotion and archiving are human transitions; the system may only demote.
 */
public final class KnowledgeCardRules {

    private KnowledgeCardRules() {}

    // ---------------------------------------------------------------- state machine

    /** What a verification does to a card in {@code state}. */
    public enum VerifyStep {
        /** The verification is recorded in the current cycle. */
        SAME_CYCLE,
        /** A new cycle starts (re-verification after expiry or a dispute), then the verification is recorded. */
        NEW_CYCLE,
        /** Not allowed: archived, already verified, or stale because a source changed (make a new card instead). */
        REFUSED
    }

    public static VerifyStep verifyStep(KnowledgeCardState state, KnowledgeStaleReason staleReason) {
        return switch (state) {
            case DRAFT -> VerifyStep.SAME_CYCLE;
            case DISPUTED -> VerifyStep.NEW_CYCLE;
            // Only an expired card keeps its sources; one whose source changed answers from text that is gone.
            case STALE -> staleReason == KnowledgeStaleReason.EXPIRED ? VerifyStep.NEW_CYCLE : VerifyStep.REFUSED;
            case VERIFIED, ARCHIVED -> VerifyStep.REFUSED;
        };
    }

    /** The state after a verification that brought the cycle to {@code verifiers} distinct verifiers. */
    public static KnowledgeCardState afterVerification(int verifiers, int required) {
        return verifiers >= required ? KnowledgeCardState.VERIFIED : KnowledgeCardState.DRAFT;
    }

    /** The system may make a card stale while it is draft, verified or disputed; stale and archived stay as they are. */
    public static boolean canGoStale(KnowledgeCardState state) {
        return state == KnowledgeCardState.DRAFT || state == KnowledgeCardState.VERIFIED
                || state == KnowledgeCardState.DISPUTED;
    }

    /** An admin may archive any card that is not archived yet. */
    public static boolean canArchive(KnowledgeCardState state) {
        return state != KnowledgeCardState.ARCHIVED;
    }

    /** Only a VERIFIED card before its expiry is ever served. */
    public static boolean servable(KnowledgeCardState state, Instant expiresAt, Instant now) {
        return state == KnowledgeCardState.VERIFIED && expiresAt != null && now.isBefore(expiresAt);
    }

    /** A verified card with this many distinct disputes in its cycle stops being served. */
    public static boolean disputed(int disputes, int threshold) {
        return disputes >= threshold;
    }

    /** The stale reason of a document change. */
    public static KnowledgeStaleReason staleReason(com.verso.document.api.dto.DocumentChange.Kind kind) {
        return switch (kind) {
            case DELETED -> KnowledgeStaleReason.SOURCE_DELETED;
            case REPLACED -> KnowledgeStaleReason.SOURCE_REPLACED;
            case MOVED -> KnowledgeStaleReason.SOURCE_MOVED;
        };
    }

    // ---------------------------------------------------------------- matching

    /** The candidates at or above the threshold, most similar first (the lookup is already ordered). */
    public static List<Candidate> matching(List<Candidate> candidates, double threshold) {
        return candidates.stream().filter(c -> c.similarity() >= threshold && Double.isFinite(c.similarity())).toList();
    }

    // ---------------------------------------------------------------- hash binding

    /** Whether the bound text is still there. */
    public enum BindingState {
        /** Every bound hash is found on its page. */
        INTACT,
        /** At least one bound hash is missing (deleted, replaced, re-chunked or not readable by this reader). */
        BROKEN
    }

    /**
     * Compares the binding with the chunks found now. Matched by (page, hash), not by chunk id: a re-embedding that
     * keeps the text keeps the card valid; any change of the text breaks it.
     */
    public static BindingState check(Collection<Binding> bound, Collection<SourceChunk> current) {
        if (bound.isEmpty()) return BindingState.BROKEN;
        Set<String> present = current.stream().map(c -> key(c.page(), c.contentSha256())).collect(Collectors.toSet());
        for (Binding binding : bound) {
            if (!present.contains(key(binding.page(), binding.contentSha256()))) return BindingState.BROKEN;
        }
        return BindingState.INTACT;
    }

    /** The current chunk ids that carry bound text (what the nightly ranking must find). */
    public static Set<UUID> currentChunkIds(Collection<Binding> bound, Collection<SourceChunk> current) {
        Set<String> keys = bound.stream().map(b -> key(b.page(), b.contentSha256())).collect(Collectors.toSet());
        return current.stream().filter(c -> keys.contains(key(c.page(), c.contentSha256()))).map(SourceChunk::chunkId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static String key(SourcePage page, String hash) {
        return page.documentId() + "/" + page.pageNumber() + "/" + hash;
    }

    // ---------------------------------------------------------------- scope

    /** Why a card cannot be made from these sources. */
    public enum ScopeProblem {
        /** A cited page has no chunk the admin can read now (deleted, moved away, or no access). */
        SOURCE_UNAVAILABLE,
        /** A cited document is a personal one: verified knowledge is shared knowledge (collections only). */
        PERSONAL_SOURCE
    }

    /**
     * The card's scope: the collections of its cited documents, in citation order. Every cited page must have at least
     * one chunk the admin reads now, and every cited document must be in a collection. The card is then visible only to
     * a caller who can read all of them, which the serving path checks again with the caller's own principals.
     */
    public static Scope scope(Collection<SourcePage> cited, Collection<SourceChunk> found) {
        Map<SourcePage, List<SourceChunk>> byPage = found.stream().collect(Collectors.groupingBy(SourceChunk::page));
        Set<UUID> collections = new LinkedHashSet<>();
        for (SourcePage page : cited) {
            List<SourceChunk> chunks = byPage.get(page);
            if (chunks == null || chunks.isEmpty()) return Scope.refused(ScopeProblem.SOURCE_UNAVAILABLE);
            for (SourceChunk chunk : chunks) {
                if (chunk.collectionId() == null) return Scope.refused(ScopeProblem.PERSONAL_SOURCE);
                collections.add(chunk.collectionId());
            }
        }
        if (collections.isEmpty()) return Scope.refused(ScopeProblem.SOURCE_UNAVAILABLE);
        return new Scope(List.copyOf(collections), null);
    }

    /** The collections of a card, or the reason it has none. */
    public record Scope(List<UUID> collections, ScopeProblem problem) {

        static Scope refused(ScopeProblem problem) {
            return new Scope(List.of(), problem);
        }

        public Optional<ScopeProblem> refusal() {
            return Optional.ofNullable(problem);
        }
    }

    // ---------------------------------------------------------------- disputes

    /**
     * The pseudonym of a disputing account for one card: SHA-256 of {@code verso:dispute:<card>:<sub>}. One user counts
     * once per card and cycle, and the same user's rows of two cards cannot be linked (no user-level drill-down).
     */
    public static byte[] disputant(UUID cardId, String accountId) {
        Objects.requireNonNull(cardId, "cardId");
        Objects.requireNonNull(accountId, "accountId");
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(("verso:dispute:" + cardId + ":" + accountId).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
