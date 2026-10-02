package com.verso.qa.exception;

import com.verso.platform.core.exception.ServiceException;

/** A failed question; the reason is a fixed word for the log, never the question, a passage or provider text. */
public class QaServiceException extends ServiceException {

    public QaServiceException(QaErrorCode errorCode, String safeLogReason) {
        super(errorCode, safeLogReason);
    }
}
