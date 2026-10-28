package com.verso.qa.service.impl;

import com.verso.document.api.DocumentCatalog;
import com.verso.document.api.dto.DocumentAccess;
import com.verso.document.api.dto.Reader;
import com.verso.qa.api.dto.HomeFeedResponse;
import com.verso.qa.repository.KnowledgeCardRepository;
import com.verso.qa.repository.QaActivityRepository;
import com.verso.qa.repository.QaActivityRepository.StateRow;
import com.verso.qa.repository.SkillRepository;
import com.verso.qa.service.HomeFeedService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/**
 * The home feed (ADR-0058). Reads the newest verified cards and approved procedures (a bounded scan of
 * {@value #SCAN} each), keeps those whose every source the caller can read now (one catalog call with the caller's own
 * principals, ADR-0028) and returns at most {@value HomeFeedService#PER_KIND} of each. A card or procedure without
 * sources is never shown: nothing proves the caller may read it.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HomeFeedServiceImpl implements HomeFeedService {

    private static final int SCAN = 25;

    private final QaActivityRepository activity;
    private final KnowledgeCardRepository cards;
    private final SkillRepository skills;
    private final DocumentCatalog catalog;
    private final Clock clock;

    public HomeFeedServiceImpl(QaActivityRepository activity, KnowledgeCardRepository cards, SkillRepository skills,
                               DocumentCatalog catalog, Clock clock) {
        this.activity = activity;
        this.cards = cards;
        this.skills = skills;
        this.catalog = catalog;
        this.clock = clock;
    }

    @Override
    public HomeFeedResponse feed(Reader reader) {
        var now = clock.instant().plusSeconds(1);
        List<StateRow> verified = activity.cards("VERIFIED", now, SCAN);
        List<StateRow> approved = activity.skills("APPROVED", now, SCAN);
        Map<UUID, Set<UUID>> cardSources = cards.citedDocuments(verified.stream().map(StateRow::id).toList());
        Map<UUID, Set<UUID>> skillSources = skills.sourceDocuments(approved.stream().map(StateRow::id).toList());
        Set<UUID> documents = new HashSet<>();
        cardSources.values().forEach(documents::addAll);
        skillSources.values().forEach(documents::addAll);
        Map<UUID, DocumentAccess> access = documents.isEmpty() ? Map.of() : catalog.access(reader, documents);

        List<UUID> readableCards = verified.stream().map(StateRow::id)
                .filter(id -> readable(cardSources.get(id), access)).limit(PER_KIND).toList();
        List<StateRow> readableSkills = approved.stream().filter(r -> readable(skillSources.get(r.id()), access))
                .limit(PER_KIND).toList();
        Map<UUID, String> questions = activity.questions(readableCards);
        Map<UUID, String> descriptions = readableSkills.isEmpty() ? Map.of()
                : skills.descriptions(readableSkills.stream().map(StateRow::id).toList());

        List<HomeFeedResponse.Output> outputs = new ArrayList<>();
        for (StateRow r : verified) {
            String question = questions.get(r.id());
            if (question != null) outputs.add(new HomeFeedResponse.Output("CARD", r.id(), question, null, r.updatedAt()));
        }
        for (StateRow r : readableSkills) {
            outputs.add(new HomeFeedResponse.Output("PROCEDURE", r.id(), r.name(), descriptions.get(r.id()),
                    r.updatedAt()));
        }
        outputs.sort(Comparator.comparing(HomeFeedResponse.Output::at).reversed());
        return new HomeFeedResponse(outputs);
    }

    static boolean readable(Set<UUID> sources, Map<UUID, DocumentAccess> access) {
        if (sources == null || sources.isEmpty()) return false;
        for (UUID id : sources) {
            DocumentAccess a = access.get(id);
            if (a == null || !a.accessible()) return false;
        }
        return true;
    }
}
