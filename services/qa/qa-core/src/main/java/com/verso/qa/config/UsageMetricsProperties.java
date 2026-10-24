package com.verso.qa.config;

import java.time.DateTimeException;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Admin usage metrics (ADR-0023).
 *
 * @param timeZone                calendar days of the rollups (a Turkish installation counts Turkish days)
 * @param showUnansweredQuestions whether admins may see the texts of "not found" questions at all; off by default, the
 *                                data controller turns it on per installation (VERSO_SHOW_UNANSWERED_QUESTIONS) after
 *                                deciding that this purpose is covered by the employees' information notice
 * @param showDocumentNames       whether admins see the file names of the top and never cited documents; off by
 *                                default (ids and counts only): today documents belong to one account each, so a name
 *                                with its daily citations can tell what one employee looked up. The data controller
 *                                turns it on (VERSO_SHOW_DOCUMENT_NAMES), e.g. for a shared corporate knowledge base
 * @param unansweredMinUsers      k of the k-anonymity rule: a question is listed only when at least this many distinct
 *                                accounts asked it; never below 3
 * @param unansweredLimit         at most this many questions in the list
 * @param maxRangeDays            longest from..to range of one request
 */
@ConfigurationProperties("verso.qa.metrics")
public record UsageMetricsProperties(
        @DefaultValue("Europe/Istanbul") String timeZone,
        @DefaultValue("false") boolean showUnansweredQuestions,
        @DefaultValue("false") boolean showDocumentNames,
        @DefaultValue("3") int unansweredMinUsers,
        @DefaultValue("20") int unansweredLimit,
        @DefaultValue("366") int maxRangeDays) {

    /** The k-anonymity floor (ADR-0023): configuration may raise it, never lower it. */
    public static final int MIN_DISTINCT_USERS_FLOOR = 3;

    public UsageMetricsProperties {
        try {
            ZoneId.of(timeZone);
        } catch (DateTimeException | NullPointerException e) {
            throw new IllegalStateException("verso.qa.metrics.time-zone is not a time zone id");
        }
        if (unansweredMinUsers < MIN_DISTINCT_USERS_FLOOR) {
            throw new IllegalStateException("verso.qa.metrics.unanswered-min-users must be at least "
                    + MIN_DISTINCT_USERS_FLOOR);
        }
        if (unansweredLimit < 1 || unansweredLimit > 100) {
            throw new IllegalStateException("verso.qa.metrics.unanswered-limit must be 1..100");
        }
        if (maxRangeDays < 1 || maxRangeDays > 3660) {
            throw new IllegalStateException("verso.qa.metrics.max-range-days must be 1..3660");
        }
    }

    public ZoneId zone() {
        return ZoneId.of(timeZone);
    }
}
