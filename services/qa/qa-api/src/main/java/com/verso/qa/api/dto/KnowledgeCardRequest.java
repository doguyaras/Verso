package com.verso.qa.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * POST /v1/admin/knowledge-cards (ADR-0049): a verified answer card from one of the admin's own answers. The answer
 * text and its citations are taken from the stored turn as they are; they cannot be edited here (an approval approves
 * what was answered, with its sources).
 *
 * @param question an optional canonical wording of the question (what the card is matched on); default: the question
 *                 as asked
 * @param keyFacts optional short facts the answer must keep (golden set, ADR-0049); at most 10 of up to 200 characters
 * @param verify   true: the creator's verification is recorded at once (the panel's "Onayla")
 */
public record KnowledgeCardRequest(@NotNull UUID conversationId, @NotNull UUID turnId,
                                   @Size(min = 1, max = 1000) @Pattern(regexp = NO_HIDDEN) String question,
                                   @Size(max = 10) List<@NotBlank @Size(max = 200) @Pattern(regexp = NO_HIDDEN) String> keyFacts,
                                   boolean verify) {

    /** No control or format characters (bidi overrides): the text is shown to other admins and users. */
    public static final String NO_HIDDEN = "[^\\p{Cc}\\p{Cf}]*";

    @Override
    public String toString() {
        return "KnowledgeCardRequest[turnId=" + turnId + ", verify=" + verify + "]";
    }
}
