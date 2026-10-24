package com.verso.privacy.service;

import com.verso.privacy.exception.PrivacyErrorCode;
import com.verso.privacy.exception.PrivacyServiceException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The token subject as a request target (ADR-0034). Only a value that a validated access token could carry as its
 * {@code sub} is accepted: the same shape JwtValidation enforces at sign-in (platform-security), so a request cannot
 * name something no account could ever be, and no SQL wildcard or control character reaches a query.
 */
public final class SubjectIds {

    /** Must stay equal to JwtValidation.SUBJECT (platform-security); SubjectIdsTest pins the cases. */
    private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9._@:-]{1,255}");

    private SubjectIds() {}

    /** The sub when it has a token subject's shape; otherwise 400 SUBJECT_INVALID (the value is never logged). */
    public static String require(String sub) {
        if (sub == null || !SUBJECT.matcher(sub).matches()) {
            throw new PrivacyServiceException(PrivacyErrorCode.SUBJECT_INVALID, "SUBJECT_FORMAT");
        }
        return sub;
    }

    /**
     * The id under which the audit log records a request about this subject (AuditTargetType.SUBJECT): a name-based UUID
     * of the sub. Recomputable from the sub, so the request can be proven later; it does not spell the sub out.
     */
    public static UUID auditRef(String sub) {
        return UUID.nameUUIDFromBytes(("verso:subject:" + sub).getBytes(StandardCharsets.UTF_8));
    }
}
