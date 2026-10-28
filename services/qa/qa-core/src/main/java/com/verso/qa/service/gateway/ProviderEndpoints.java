package com.verso.qa.service.gateway;
import com.verso.qa.repository.ModelConnection;

import com.verso.qa.api.dto.ModelConnectionRequest;
import com.verso.qa.api.dto.ModelProviderType;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.boot.http.client.InetAddressFilter;

/**
 * Where each provider type sends data and what it needs (ADR-0056): default endpoints, the egress host a connection
 * adds to the allowlist, the field rules of the type-specific form, and the locality (internal network or not). Pure
 * functions plus one DNS lookup: no I/O beyond resolving a model server's name.
 */
public final class ProviderEndpoints {

    private static final InetAddressFilter INTERNAL = InetAddressFilter.internalAddresses();

    private ProviderEndpoints() {
    }

    /** The endpoint a type uses when the admin gives none; null when the type needs one. */
    public static String defaultBaseUrl(ModelProviderType type) {
        return switch (type) {
            case OPENAI -> "https://api.openai.com/v1";
            case ANTHROPIC -> "https://api.anthropic.com";
            // Mistral's chat API is OpenAI-compatible; the OpenAI client keeps one HTTP stack and one egress guard.
            case MISTRAL -> "https://api.mistral.ai/v1";
            case AZURE_OPENAI, OLLAMA, OPENAI_COMPATIBLE, BEDROCK, GOOGLE_GENAI -> null;
        };
    }

    /** The host the connection reaches: the given base URL's, else the SDK's documented default for the settings. */
    public static String egressHost(ModelProviderType type, String baseUrl, String region, String project) {
        String url = blankToNull(baseUrl) != null ? baseUrl : defaultBaseUrl(type);
        if (url != null) return hostOf(url);
        return switch (type) {
            case BEDROCK -> "bedrock-runtime." + region + ".amazonaws.com";
            case GOOGLE_GENAI -> blankToNull(project) != null ? region + "-aiplatform.googleapis.com"
                    : "generativelanguage.googleapis.com";
            default -> null;
        };
    }

    /** Field rules of the form; a violation is 400 {@code MODEL_CONNECTION_INVALID} with a fixed reason word. */
    public static void validate(ModelConnectionRequest request, boolean keyGiven, boolean keyStored) {
        ModelProviderType type = request.type();
        String baseUrl = blankToNull(request.baseUrl());
        if (baseUrl != null) requireUrl(baseUrl);
        switch (type) {
            case AZURE_OPENAI -> {
                require(baseUrl != null, "BASE_URL_REQUIRED");
                require(blankToNull(request.deployment()) != null, "DEPLOYMENT_REQUIRED");
            }
            case OLLAMA, OPENAI_COMPATIBLE -> require(baseUrl != null, "BASE_URL_REQUIRED");
            case BEDROCK -> require(blankToNull(request.region()) != null, "REGION_REQUIRED");
            case GOOGLE_GENAI -> require(blankToNull(request.project()) == null
                    || blankToNull(request.region()) != null, "REGION_REQUIRED");
            case OPENAI, ANTHROPIC, MISTRAL -> {
            }
        }
        boolean needsKey = type != ModelProviderType.OLLAMA && type != ModelProviderType.OPENAI_COMPATIBLE;
        require(!needsKey || keyGiven || keyStored, "KEY_REQUIRED");
        require(type != ModelProviderType.OLLAMA || !keyGiven, "KEY_NOT_USED");
        if (keyGiven) validateKey(type, request.apiKey(), blankToNull(request.project()) != null);
    }

    /** The key's shape for the type (never its value in a message). */
    public static void validateKey(ModelProviderType type, String key, boolean vertex) {
        String k = key.strip();
        require(!k.isEmpty() && k.chars().noneMatch(c -> c == '\n' || c == '\r') || (vertex && k.startsWith("{")),
                "KEY_MALFORMED");
        if (type == ModelProviderType.BEDROCK) {
            int colon = k.indexOf(':');
            require(colon > 0 && colon < k.length() - 1, "KEY_MALFORMED");
        }
        if (type == ModelProviderType.GOOGLE_GENAI && vertex) require(k.startsWith("{"), "KEY_MALFORMED");
    }

    /**
     * LOCAL when the type can run on the internal network and every address of its host is internal (loopback,
     * link-local, private); EXTERNAL otherwise, including a name that does not resolve (fail closed).
     */
    public static ModelConnection.Locality locality(ModelProviderType type, String host) {
        if (!type.selfHostable() || host == null) return ModelConnection.Locality.EXTERNAL;
        return internal(host) ? ModelConnection.Locality.LOCAL : ModelConnection.Locality.EXTERNAL;
    }

    static boolean internal(String host) {
        String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        try {
            InetAddress[] addresses = InetAddress.getAllByName(literal);
            if (addresses.length == 0) return false;
            for (InetAddress address : addresses) {
                if (!INTERNAL.matches(address)) return false;
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * http or https, a host, no user info, no query or fragment. Plain http only to an internal host: a key never
     * crosses a network unencrypted.
     */
    private static void requireUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw invalid("BASE_URL_MALFORMED");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        require(scheme.equals("https") || scheme.equals("http"), "BASE_URL_SCHEME");
        require(uri.getHost() != null && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                && uri.getRawFragment() == null, "BASE_URL_MALFORMED");
        require(scheme.equals("https") || internal(uri.getHost()), "BASE_URL_NOT_HTTPS");
        require(!metadataOrUnspecified(uri.getHost()), "BASE_URL_FORBIDDEN");
    }

    /**
     * Link-local (cloud metadata services such as 169.254.169.254, fe80::/10) and unspecified addresses are never a
     * model server: refused at save and on every request (security review B2: an admin form must not become a probe of
     * the host's metadata service). An unresolvable name is not refused here; the call then fails on its own.
     */
    static boolean metadataOrUnspecified(String host) {
        if (host == null) return true;
        String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        try {
            for (InetAddress address : InetAddress.getAllByName(literal)) {
                if (address.isLinkLocalAddress() || address.isAnyLocalAddress()) return true;
            }
            return false;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw invalid(reason);
    }

    private static QaServiceException invalid(String reason) {
        return new QaServiceException(QaErrorCode.MODEL_CONNECTION_INVALID, reason);
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
