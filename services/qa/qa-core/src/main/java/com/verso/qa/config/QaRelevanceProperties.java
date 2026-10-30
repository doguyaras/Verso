package com.verso.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Answer relevance and abstention (ADR-0064): after the citations are validated, a model answer that is a refusal
 * ("Belgelerde … bilgi bulunmamaktadır [4]"), opens with one and adds only loosely related text, or does not give what
 * the question asks for (a person for "kim", a number in the asked unit for "kaç", a place for "nerede") becomes the
 * fixed "not found" answer: no citations, {@code found=false}. In-process and lexical: no model, no network, the same
 * in both modes.
 *
 * @param enabled off: the outcome is the citations' and the groundedness check's alone (before ADR-0064)
 */
@ConfigurationProperties("verso.qa.relevance")
public record QaRelevanceProperties(@DefaultValue("true") boolean enabled) {
}
