package com.verso.connector.smb;

import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Reads a connector's password (ADR-0038, security-rules 1): the secret file {@code /run/secrets/SECRET_CONNECTOR_*}
 * that the config tree turns into a property of the same name (application.yml imports /run/secrets; locally
 * secrets/). Only {@code SECRET_CONNECTOR_*} names are ever read, so an admin cannot point a connector at the database
 * or IdP secrets and send them to a file server. The value is handed to the SMB client as a char array and never
 * logged, stored, returned or compared in a log line.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConnectorCredentials {

    private static final Pattern NAME = Pattern.compile("SECRET_CONNECTOR_[A-Z0-9_]{1,60}");

    private final Environment environment;

    public ConnectorCredentials(Environment environment) {
        this.environment = environment;
    }

    /**
     * @throws SourceException fatal CREDENTIAL_MISSING when the name is not a connector secret or the file is absent
     */
    public char[] password(String credentialRef) {
        if (credentialRef == null || !NAME.matcher(credentialRef).matches()) {
            throw SourceException.fatal("CREDENTIAL_MISSING");
        }
        String value = environment.getProperty(credentialRef);
        if (value == null || value.isEmpty()) throw SourceException.fatal("CREDENTIAL_MISSING");
        // A file written with a trailing newline (echo instead of printf) is the usual mistake; a line break is no part
        // of a Windows password. Spaces are kept: they may be.
        int end = value.length();
        while (end > 0 && (value.charAt(end - 1) == '\n' || value.charAt(end - 1) == '\r')) end--;
        if (end == 0) throw SourceException.fatal("CREDENTIAL_MISSING");
        return value.substring(0, end).toCharArray();
    }
}
