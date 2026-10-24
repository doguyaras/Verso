package com.verso.privacy.exception;

import com.verso.platform.core.exception.ServiceException;

/** A refused privacy request; the reason is a fixed word for the log, never the sub or another parameter value. */
public class PrivacyServiceException extends ServiceException {

    public PrivacyServiceException(PrivacyErrorCode errorCode, String safeLogReason) {
        super(errorCode, safeLogReason);
    }
}
