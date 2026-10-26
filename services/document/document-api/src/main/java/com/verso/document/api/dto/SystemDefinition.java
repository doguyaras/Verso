package com.verso.document.api.dto;

/**
 * A DEFINES relation as a reader sees it (ADR-0054): the definition text (an expansion such as "First Expired First
 * Out", or a code's description) and the chunk it was read from. Document text: never logged.
 */
public record SystemDefinition(String value, SystemSource source) {

    @Override
    public String toString() {
        return "SystemDefinition[source=" + source + "]";
    }
}
