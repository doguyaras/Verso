package com.verso.qa.config;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The mode is a promise (ADR-0006): "local" means no model call leaves the host, "cloud" means only the chat call does.
 * This check refuses to start an application whose model settings break that promise, instead of answering with a
 * header that lies (ADR-0006 decision 1.3, ADR-0013):
 *
 * <ul>
 *   <li>embeddings come from Ollama in both modes (llm-rules 1.2);</li>
 *   <li>local: the chat model is Ollama too, or an OpenAI-compatible model server on the internal network (the shared
 *       tier, ADR-0051: llama.cpp's llama-server); a model server address given as an IP literal is an internal one (a
 *       host name is checked on every connection by the outbound address filter and {@link LocalModelEgress} instead:
 *       at startup the model server may not be resolvable yet). The local server's key follows the cloud key's rules;</li>
 *   <li>cloud: the chat model is a cloud provider and its API key is present, from /run/secrets: a key in an environment
 *       variable or a system property is refused (llm-rules 1.4), and so is the SDKs' own HTTP logging switch
 *       (ANTHROPIC_LOG, OPENAI_LOG), which would write request metadata to the log (llm-rules 2.1).</li>
 * </ul>
 *
 * Messages name settings, never values.
 */
public final class AiModeCheck {

    static final String CHAT_PROVIDER = "spring.ai.model.chat";
    static final String EMBEDDING_PROVIDER = "spring.ai.model.embedding";
    static final String OLLAMA_BASE_URL = "spring.ai.ollama.base-url";
    static final String OPENAI_BASE_URL = "spring.ai.openai.base-url";
    static final String OPENAI_API_KEY = "spring.ai.openai.api-key";
    /** Embeddings: Ollama only, in every mode (llm-rules 1.2). */
    static final Set<String> LOCAL_PROVIDERS = Set.of("ollama");
    /** Chat in local mode: Ollama, or an OpenAI-compatible model server on the internal network (ADR-0051). */
    static final List<String> LOCAL_CHAT_PROVIDERS = List.of("ollama", "openai"); // a list: a stable order in messages
    /** Cloud provider → the setting that must hold its API key. */
    static final Map<String, String> CLOUD_PROVIDERS = Map.of(
            "anthropic", "spring.ai.anthropic.api-key",
            "openai", "spring.ai.openai.api-key");

    /** Environment switches of the provider SDKs' own HTTP logging. */
    static final List<String> SDK_LOG_SWITCHES = List.of("ANTHROPIC_LOG", "OPENAI_LOG");

    private AiModeCheck() {
    }

    /** True when the source holds the setting under any relaxed spelling (SPRING_AI_ANTHROPIC_APIKEY, ...). */
    private static boolean definedIn(ConfigurableEnvironment environment, String sourceName, String setting) {
        PropertySource<?> source = environment.getPropertySources().get(sourceName);
        if (!(source instanceof EnumerablePropertySource<?> enumerable)) return false;
        String wanted = canonical(setting);
        for (String name : enumerable.getPropertyNames()) {
            if (canonical(name).equals(wanted)) return true;
        }
        return false;
    }

    private static String canonical(String name) {
        return name.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    public static void verify(AiMode mode, Environment environment) {
        String embedding = environment.getProperty(EMBEDDING_PROVIDER, "");
        if (!LOCAL_PROVIDERS.contains(embedding)) {
            throw new IllegalStateException(EMBEDDING_PROVIDER + " must be ollama in every mode (llm-rules 1.2)");
        }
        // Documents and questions are embedded by Ollama in both modes: its address is checked in both (review F4).
        requireInternalIfLiteral(OLLAMA_BASE_URL, environment.getProperty(OLLAMA_BASE_URL, ""));
        String chat = environment.getProperty(CHAT_PROVIDER, "");
        switch (mode) {
            case LOCAL -> {
                if (!LOCAL_CHAT_PROVIDERS.contains(chat)) {
                    throw new IllegalStateException("verso.ai.mode=local needs " + CHAT_PROVIDER + " to be one of "
                            + LOCAL_CHAT_PROVIDERS + "; a cloud provider is only allowed with verso.ai.mode=cloud "
                            + "(ADR-0006)");
                }
                if (chat.equals("openai")) {
                    // The shared tier (ADR-0051): the same client as the cloud provider, aimed at a model server on
                    // the internal network. Its address must stay internal, like Ollama's.
                    requireInternalIfLiteral(OPENAI_BASE_URL, environment.getProperty(OPENAI_BASE_URL, ""));
                    requireSecretKey(environment, OPENAI_API_KEY, "deploy/compose.llamacpp.yaml");
                }
            }
            case CLOUD -> {
                String keySetting = CLOUD_PROVIDERS.get(chat);
                if (keySetting == null) {
                    throw new IllegalStateException("verso.ai.mode=cloud needs " + CHAT_PROVIDER + " to be one of "
                            + CLOUD_PROVIDERS.keySet() + " (ADR-0013)");
                }
                requireSecretKey(environment, keySetting, "deploy/compose.cloud.yaml");
            }
        }
    }

    /**
     * The key of the chat client comes from /run/secrets, never from an environment variable or a system property
     * (llm-rules 1.4), and the SDKs' own request logging stays off (llm-rules 2.1).
     */
    private static void requireSecretKey(Environment environment, String keySetting, String overlay) {
        String key = environment.getProperty(keySetting, "");
        if (key.isBlank()) {
            throw new IllegalStateException(keySetting + " is missing: it comes from /run/secrets (" + overlay
                    + "), never from an environment variable");
        }
        if (environment instanceof ConfigurableEnvironment configurable && (definedIn(configurable,
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, keySetting)
                || definedIn(configurable, StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, keySetting))) {
            throw new IllegalStateException(keySetting + " must come from /run/secrets, not from an environment "
                    + "variable or a system property (llm-rules 1.4)");
        }
        for (String logSwitch : SDK_LOG_SWITCHES) {
            if (!environment.getProperty(logSwitch, "").isBlank()) {
                throw new IllegalStateException(logSwitch + " is set: the provider SDK would log requests "
                        + "(llm-rules 2.1); unset it");
            }
        }
    }

    private static void requireInternalIfLiteral(String setting, String baseUrl) {
        String host;
        try {
            URI uri = URI.create(baseUrl);
            // getHost() is null for names java.net.URI does not accept, such as Docker's "my_ollama" (review F10).
            host = uri.getHost() != null ? uri.getHost() : hostOf(uri.getRawAuthority());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(setting + " is not a valid URL");
        }
        if (host == null || host.isBlank()) throw new IllegalStateException(setting + " has no host");
        String literal = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (!isIpLiteral(literal)) return;
        try {
            // An IP literal is parsed, not looked up.
            if (!InetAddressFilter.internalAddresses().matches(InetAddress.getByName(literal))) {
                throw new IllegalStateException(setting + " points outside the internal network; "
                        + "verso.ai.mode=local keeps every model call on this host (ADR-0006)");
            }
        } catch (java.net.UnknownHostException e) {
            throw new IllegalStateException(setting + " is not a valid address");
        }
    }

    private static String hostOf(String authority) {
        if (authority == null) return null;
        String withoutUser = authority.substring(authority.indexOf('@') + 1);
        if (withoutUser.startsWith("[")) return withoutUser.substring(0, withoutUser.indexOf(']') + 1);
        int colon = withoutUser.indexOf(':');
        return colon < 0 ? withoutUser : withoutUser.substring(0, colon);
    }

    private static boolean isIpLiteral(String host) {
        return host.contains(":") || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }
}
