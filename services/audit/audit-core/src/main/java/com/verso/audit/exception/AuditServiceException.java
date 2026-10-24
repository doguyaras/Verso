package com.verso.audit.exception;

import com.verso.platform.core.exception.ServiceException;

/** A refused audit query; the reason is a fixed word for the log, never a parameter value. */
public class AuditServiceException extends ServiceException {

    public AuditServiceException(AuditErrorCode errorCode, String safeLogReason) {
        super(errorCode, safeLogReason);
    }
}
