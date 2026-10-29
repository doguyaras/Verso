package com.verso.qa.api.dto;

import jakarta.validation.constraints.AssertTrue;

/**
 * Gateway settings a verso-admin changes (ADR-0056, ADR-0059). Each field is optional and null leaves that setting as
 * it is (a client that sends only {@code userModelChoice} keeps working); at least one must be given. Never a silent
 * default: an absent field is never read as false.
 *
 * @param userModelChoice whether users may pick among the answer route's models
 * @param piiMasking      whether Turkish personal data is replaced by placeholders before a cloud call (ADR-0059)
 */
public record ModelGatewaySettingsRequest(Boolean userModelChoice, Boolean piiMasking) {

    @AssertTrue(message = "at least one setting is required")
    public boolean isAnySetting() {
        return userModelChoice != null || piiMasking != null;
    }
}
