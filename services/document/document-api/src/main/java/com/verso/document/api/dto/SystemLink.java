package com.verso.document.api.dto;

import com.verso.document.api.enums.SystemEntityType;
import com.verso.document.api.enums.SystemRelationType;

/**
 * A relation of an entity to another entity (ADR-0054), from a document the reader may read.
 *
 * @param outgoing true for "this entity RELATION other" (a STEP_OF its process), false for the reverse (a process's
 *                 steps)
 * @param ordinal  the step number when {@code type} is STEP, else null
 */
public record SystemLink(SystemRelationType relation, boolean outgoing, SystemEntityType type, String key,
                         String label, Integer ordinal) {
}
