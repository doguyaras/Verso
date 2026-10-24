package com.verso.connector.exception;

import com.verso.platform.core.exception.ServiceException;

/** A refused connector request; the reason is a fixed word for the log, never a host, path or name. */
public class ConnectorServiceException extends ServiceException {

    public ConnectorServiceException(ConnectorErrorCode errorCode, String safeLogReason) {
        super(errorCode, safeLogReason);
    }
}
