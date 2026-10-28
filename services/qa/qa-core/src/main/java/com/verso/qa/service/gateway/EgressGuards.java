package com.verso.qa.service.gateway;

import java.io.IOException;
import java.util.Locale;
import okhttp3.Interceptor;
import okhttp3.Response;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;

/**
 * The cloud-mode egress allowlist of the gateway (ADR-0056): every client a connection builds may reach that
 * connection's own host and nothing else, so the allowlist is exactly the hosts of the configured connections (the
 * "Güven" screen lists them). Local mode uses {@code LocalModelEgress} instead (internal addresses only). Messages name
 * no host: the value is the operator's (llm-rules 2.1).
 *
 * <p>Limits: as with {@code LocalModelEgress}, OkHttp follows redirects inside {@code proceed}; a redirect to another
 * host is refused afterwards and its answer is not used. The Google GenAI SDK has no interceptor seam: its host is
 * the SDK's fixed endpoint for the settings (or the admin's base URL), and the network (the egress overlay) is the
 * guarantee there.
 */
final class EgressGuards {

    private EgressGuards() {
    }

    /** OkHttp (OpenAI, Azure OpenAI, Mistral, OpenAI-compatible, Anthropic clients): only {@code host}. */
    static Interceptor okHttp(String host) {
        String allowed = host.toLowerCase(Locale.ROOT);
        return chain -> {
            if (!allowed.equals(chain.request().url().host().toLowerCase(Locale.ROOT))) {
                throw new IOException("Model gateway refused: the host is not this connection's (ADR-0056)");
            }
            // The name may resolve differently than at save: a metadata or unspecified address is refused here too.
            if (ProviderEndpoints.metadataOrUnspecified(chain.request().url().host())) {
                throw new IOException("Model gateway refused: a link-local or unspecified address (ADR-0056)");
            }
            Response response = chain.proceed(chain.request());
            if (!allowed.equals(response.request().url().host().toLowerCase(Locale.ROOT))) {
                response.close();
                throw new IOException("Model gateway refused: redirected to another host (ADR-0056)");
            }
            return response;
        };
    }

    /** AWS SDK (Bedrock): checked before each request is sent. */
    static ExecutionInterceptor aws(String host) {
        String allowed = host.toLowerCase(Locale.ROOT);
        return new ExecutionInterceptor() {
            @Override
            public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
                if (!allowed.equals(context.httpRequest().host().toLowerCase(Locale.ROOT))) {
                    throw new IllegalStateException("Model gateway refused: the host is not this connection's");
                }
            }
        };
    }
}
