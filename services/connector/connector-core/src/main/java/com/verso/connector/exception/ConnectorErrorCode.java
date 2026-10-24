package com.verso.connector.exception;

import com.verso.platform.core.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** Codes of the connector block, 14000-14999 (ADR-0004, ADR-0038, ErrorCodeUniquenessTest). */
public enum ConnectorErrorCode implements ErrorCode {

    // --- admin API (14000-14049)
    CONNECTOR_NOT_FOUND(14001, "Connector not found.", HttpStatus.NOT_FOUND),
    CONNECTOR_NAME_TAKEN(14002, "A connector with this name exists.", HttpStatus.CONFLICT),
    CONNECTOR_PATH_INVALID(14003, "The path prefix is not a safe relative folder of the share.", HttpStatus.BAD_REQUEST),
    CONNECTOR_HOST_NOT_ALLOWED(14004, "The host is outside the networks this installation may reach.",
            HttpStatus.BAD_REQUEST),
    CONNECTOR_COLLECTION_NOT_FOUND(14005, "The target collection does not exist.", HttpStatus.BAD_REQUEST),
    CONNECTOR_COLLECTION_IMMUTABLE(14006, "The target collection of a connector cannot change.", HttpStatus.CONFLICT),
    CONNECTOR_DELETING(14007, "The connector is being deleted.", HttpStatus.CONFLICT),
    CONNECTOR_LIMIT_REACHED(14008, "The installation has the maximum number of connectors.", HttpStatus.CONFLICT),
    CONNECTOR_GLOB_INVALID(14009, "An include or exclude pattern is invalid.", HttpStatus.BAD_REQUEST),
    CONNECTOR_FILE_SIZE_INVALID(14010, "The maximum file size exceeds the document limit.", HttpStatus.BAD_REQUEST),
    CONNECTOR_PAGE_INVALID(14011, "The page or size is out of range.", HttpStatus.BAD_REQUEST),
    // --- ADR-0044 (Confluence)
    CONNECTOR_SETTINGS_INVALID(14012, "The settings do not fit the connector type.", HttpStatus.BAD_REQUEST),
    CONNECTOR_EXTERNAL_EGRESS_DISABLED(14013, "This installation does not allow connectors to reach this host.",
            HttpStatus.BAD_REQUEST),
    CONNECTOR_URL_INVALID(14014, "The site URL is not a valid https URL.", HttpStatus.BAD_REQUEST),
    CONNECTOR_TYPE_IMMUTABLE(14015, "The type of a connector cannot change.", HttpStatus.CONFLICT);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ConnectorErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public String getService() {
        return "connector";
    }

    @Override
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
