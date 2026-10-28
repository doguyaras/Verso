package com.verso.document.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterUtils;

/**
 * Regression (CI on e033cfa, ADR-0058): the day query once sliced an array with "[1:3]"; JdbcClient's named-parameter
 * parser took ":3" for a parameter, the statement failed and the activity centre answered "partial". Each statement
 * must resolve with exactly the parameters the repository binds.
 */
class DocumentActivityRepositoryTest {

    private static Object[] resolve(String sql, MapSqlParameterSource bound) {
        return NamedParameterUtils.buildValueArray(NamedParameterUtils.parseSqlStatement(sql), bound, null);
    }

    @Test
    void statements_whenParsed_needOnlyTheBoundParameters() {
        MapSqlParameterSource changes = new MapSqlParameterSource().addValue("principals", new String[] {"user:a"})
                .addValue("from", 1).addValue("to", 2).addValue("collection", UUID.randomUUID()).addValue("limit", 3);
        assertThat(resolve(DocumentActivityRepository.CHANGES_SQL, changes)).isNotEmpty();
        MapSqlParameterSource extractions = new MapSqlParameterSource().addValue("principals", new String[] {"user:a"})
                .addValue("from", 1).addValue("to", 2).addValue("limit", 3);
        assertThat(resolve(DocumentActivityRepository.EXTRACTIONS_SQL, extractions)).isNotEmpty();
    }

    @Test
    void parser_whenAnArraySliceIsWritten_takesItForAParameter() {
        // Why the statements above must not slice arrays: the parser asks for a value named "3".
        assertThatThrownBy(() -> resolve("SELECT (array_agg(x))[1:3] FROM t WHERE y = :limit",
                new MapSqlParameterSource().addValue("limit", 1))).isInstanceOf(InvalidDataAccessApiUsageException.class);
    }
}
