package com.verso.qa.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.api.dto.SkillDraftRequest;
import com.verso.qa.api.dto.SkillUpdateRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The skill request bodies (ADR-0053) as Jackson 3 reads them with its defaults (the application's mapper): a body
 * without the optional {@code condense} switch must bind (Jackson 3 refuses a missing primitive creator argument by
 * default, FAIL_ON_NULL_FOR_PRIMITIVES), and the documented shapes must round-trip.
 */
class SkillRequestJsonTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void draft_withoutOptionalFields_binds_andCondenseDefaultsToFalse() {
        UUID card = UUID.randomUUID();
        SkillDraftRequest manual = json.readValue("{\"cardIds\":[\"" + card + "\"],\"name\":\"izin-sureci\"}",
                SkillDraftRequest.class);
        assertThat(manual.cardIds()).containsExactly(card);
        assertThat(manual.condense()).isFalse();
        UUID cluster = UUID.randomUUID();
        SkillDraftRequest fromCluster = json.readValue("{\"clusterId\":\"" + cluster + "\"}", SkillDraftRequest.class);
        assertThat(fromCluster.clusterId()).isEqualTo(cluster);
        assertThat(fromCluster.condense()).isFalse();
        assertThat(json.readValue("{}", SkillDraftRequest.class).condense()).isFalse();
        assertThat(json.readValue("{\"cardIds\":[\"" + card + "\"],\"condense\":true}", SkillDraftRequest.class)
                .condense()).isTrue();
        UUID conversation = UUID.randomUUID();
        SkillDraftRequest turns = json.readValue("{\"turns\":[{\"conversationId\":\"" + conversation
                + "\",\"turnId\":\"" + card + "\"}]}", SkillDraftRequest.class);
        assertThat(turns.turns()).singleElement().satisfies(t -> assertThat(t.conversationId()).isEqualTo(conversation));
    }

    @Test
    void update_inTheDocumentedShape_binds_withAndWithoutTheOptionalFact() {
        UUID document = UUID.randomUUID();
        SkillUpdateRequest update = json.readValue("{\"name\":\"izin-sureci\",\"description\":\"d\",\"steps\":[{\"from\":1,"
                + "\"instruction\":\"Adım\"}],\"triggers\":[\"Soru?\"],\"goldenCases\":[{\"question\":\"q\",\"documentId\":\""
                + document + "\",\"page\":1},{\"question\":\"r\",\"documentId\":\"" + document
                + "\",\"page\":2,\"fact\":\"bilgi\"}]}", SkillUpdateRequest.class);
        assertThat(update.steps()).singleElement().satisfies(s -> assertThat(s.from()).isEqualTo(1));
        assertThat(update.goldenCases()).hasSize(2);
        assertThat(update.goldenCases().getFirst().fact()).isNull();
        assertThat(update.goldenCases().get(1).fact()).isEqualTo("bilgi");
    }
}
