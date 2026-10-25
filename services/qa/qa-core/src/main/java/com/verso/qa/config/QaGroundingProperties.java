package com.verso.qa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The answer groundedness check (ADR-0047): after the citations are validated, every cited answer sentence that states a
 * number, a number word or a code is compared with its cited passages; a sentence they do not support is listed in
 * {@code unverified} and the panel marks it. In-process and lexical: no model, no network, the same in both modes.
 *
 * @param enabled off: no check, {@code unverified} is null and the outcome is the citations' alone (before ADR-0047)
 */
@ConfigurationProperties("verso.qa.grounding")
public record QaGroundingProperties(@DefaultValue("true") boolean enabled) {
}
