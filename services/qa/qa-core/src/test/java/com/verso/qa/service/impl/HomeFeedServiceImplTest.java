package com.verso.qa.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.api.dto.DocumentAccess;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR-0058: the home feed shows a card or procedure only when the caller reads every source of it. */
class HomeFeedServiceImplTest {

    @Test
    void readable_needsEverySourceReadable_andAtLeastOneSource() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Map<UUID, DocumentAccess> access = Map.of(a, new DocumentAccess(a, true, "a.pdf", null, null),
                b, DocumentAccess.notAccessible(b));
        assertThat(HomeFeedServiceImpl.readable(Set.of(a), access)).isTrue();
        assertThat(HomeFeedServiceImpl.readable(Set.of(a, b), access)).isFalse();
        assertThat(HomeFeedServiceImpl.readable(Set.of(a, UUID.randomUUID()), access)).as("deleted source").isFalse();
        assertThat(HomeFeedServiceImpl.readable(Set.of(), access)).isFalse();
        assertThat(HomeFeedServiceImpl.readable(null, access)).isFalse();
    }
}
