package com.verso.platform.core.exception;

import org.springframework.http.HttpStatus;

/**
 * Contract of every error code (reference 7.1). Codes are globally unique; each service owns a block
 * (README "Hata kodu bloklari", enforced by ErrorCodeUniquenessTest). Messages are short English sentences that end
 * with a period; clients translate by code, never by message.
 */
public interface ErrorCode {

    int getCode();

    String getMessage();

    /** Block owner: a service/module name, or validation / security / system for shared codes. */
    String getService();

    HttpStatus getHttpStatus();
}
