package com.verso.document.service;

import com.verso.document.api.dto.SystemEntityDetail;
import com.verso.document.api.dto.SystemEntitySummary;
import com.verso.document.api.dto.SystemModelStats;
import com.verso.document.api.enums.SystemEntityType;
import java.util.List;

/**
 * The system map and the lookup (ADR-0054), always with the caller's principals (ADR-0028): what a caller cannot read
 * is not in any answer, not even as a count.
 */
public interface SystemModelService {

    /** Entities of a type (all but steps when null) whose key starts with the folded {@code prefix}; bounded. */
    List<SystemEntitySummary> browse(Caller caller, SystemEntityType type, String prefix, int limit);

    /** One entity with its definitions, relations and sources; SYSTEM_ENTITY_NOT_FOUND when nothing readable has it. */
    SystemEntityDetail entity(Caller caller, SystemEntityType type, String key);

    /** Entities named by {@code query} (a code, an abbreviation, a name); SYSTEM_MODEL_QUERY_INVALID when blank/long. */
    List<SystemEntityDetail> lookup(Caller caller, String query);

    SystemModelStats stats(Caller caller);
}
