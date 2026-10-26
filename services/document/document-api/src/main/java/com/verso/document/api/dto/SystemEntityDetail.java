package com.verso.document.api.dto;

import com.verso.document.api.enums.SystemEntityType;
import java.util.List;

/**
 * One entity with its definitions, relations and sources (ADR-0054), read with the reader's principals: nothing from a
 * document the reader may not read is in it, not even a count.
 *
 * @param definitions DEFINES values with their source chunk, defining chunks first
 * @param links       relations to other entities (bounded)
 * @param sources     where it is mentioned (bounded; "where is this code mentioned"), defining chunks first
 * @param truncated   true when a bound cut a list
 */
public record SystemEntityDetail(SystemEntityType type, String key, String label, List<SystemDefinition> definitions,
                                 List<SystemLink> links, List<SystemSource> sources, boolean truncated) {

    public SystemEntityDetail {
        definitions = List.copyOf(definitions);
        links = List.copyOf(links);
        sources = List.copyOf(sources);
    }
}
