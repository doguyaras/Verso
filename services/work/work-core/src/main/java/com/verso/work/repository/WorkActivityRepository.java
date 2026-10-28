package com.verso.work.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Repository;

/**
 * The work module's read for the activity centre (ADR-0058): skill runs per UTC day and skill, counts only, and only
 * for groups at least {@code minOwners} distinct accounts ran (k-anonymity, ADR-0048's floor). A run belongs to one
 * person, so a single run never becomes a line of its own: {@code owner_sub} is counted in the database and never
 * selected; no input, output or prompt is read.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkActivityRepository {

    /** One skill's runs on one day. */
    public record RunDay(Instant day, String skillId, String skillName, int runs, int failed, int approved, int running,
                         Instant latest) {
    }

    private final WorkDatabase db;

    public WorkActivityRepository(WorkDatabase db) {
        this.db = db;
    }

    public List<RunDay> runDays(Instant from, Instant to, int minOwners, int limit) {
        return db.jdbc().sql("SELECT date_trunc('day', created_at, 'UTC') AS day, skill_id, "
                        + "max(definition ->> 'name') AS skill_name, count(*) AS runs, "
                        + "count(*) FILTER (WHERE status = 'FAILED') AS failed, "
                        + "count(*) FILTER (WHERE status = 'APPROVED') AS approved, "
                        + "count(*) FILTER (WHERE status = 'RUNNING') AS running, max(created_at) AS latest "
                        + "FROM work.skill_run WHERE created_at >= :from AND created_at < :to GROUP BY 1, 2 "
                        + "HAVING count(DISTINCT owner_sub) >= :k ORDER BY latest DESC LIMIT :limit")
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to)).param("k", minOwners)
                .param("limit", limit)
                .query((rs, n) -> new RunDay(rs.getTimestamp("day").toInstant(), rs.getString("skill_id"),
                        rs.getString("skill_name"), rs.getInt("runs"), rs.getInt("failed"), rs.getInt("approved"),
                        rs.getInt("running"), rs.getTimestamp("latest").toInstant())).list();
    }
}
