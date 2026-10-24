package com.verso.connector.smb;

/**
 * A source failure with a fixed code (ADR-0038). Never carries the server's message, the host, a path or a credential:
 * the code is what reaches the log, the run row and the API; the cause's type is logged at most.
 */
public class SourceException extends RuntimeException {

    /** The run as a whole cannot go on (connection, credentials, egress, share); else only the current file failed. */
    private final boolean fatal;
    private final String code;

    public SourceException(String code, boolean fatal, Throwable cause) {
        super(code, cause, false, false);
        this.code = code;
        this.fatal = fatal;
    }

    public static SourceException fatal(String code) {
        return new SourceException(code, true, null);
    }

    public static SourceException file(String code) {
        return new SourceException(code, false, null);
    }

    public String code() {
        return code;
    }

    public boolean isFatal() {
        return fatal;
    }

    /** The cause's simple type, for one log field; never its message. */
    public String causeType() {
        return getCause() == null ? "none" : getCause().getClass().getSimpleName();
    }
}
