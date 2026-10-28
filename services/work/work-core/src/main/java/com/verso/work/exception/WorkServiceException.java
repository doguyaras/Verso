package com.verso.work.exception;

import com.verso.platform.core.exception.ServiceException;
import java.util.List;

/**
 * A refused skill-library request. The reason is a fixed word for the log; the details (returned to the caller) name
 * fields and rules only, never a form value or an output text (llm-rules 2.1).
 */
public class WorkServiceException extends ServiceException {

    public WorkServiceException(WorkErrorCode errorCode, String safeLogReason) {
        super(errorCode, safeLogReason);
    }

    public WorkServiceException(WorkErrorCode errorCode, String safeLogReason, List<String> details) {
        super(errorCode, safeLogReason, null, details);
    }
}
