package com.verso.document.exception;

import com.verso.platform.core.exception.ServiceException;

/** A rejected document request; the reason is a fixed word for the log, never a file name or content. */
public class DocumentServiceException extends ServiceException {

    public DocumentServiceException(DocumentErrorCode errorCode) {
        super(errorCode, errorCode.name());
    }
}
