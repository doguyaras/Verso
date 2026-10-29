package com.verso.qa.service.gateway;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Turkish personal data pseudonymisation of external model calls (ADR-0059): before a prompt leaves the installation
 * every system, user and assistant message is masked by one {@link Pseudonymiser}; the answer (whole or streamed) gets
 * the values back. The mapping is the exchange's, so it lives exactly as long as one call and one attempt of a route
 * (a fallback model opens its own).
 *
 * <p>Fails closed: a message kind it cannot rewrite (a tool response) stops the call before anything is sent. Nothing
 * here logs; the usage row receives the counts per type only.
 */
final class PiiMaskingFilter implements ModelRequestFilter {

    static final String POLICY = "PII_PSEUDONYMISE";

    @Override
    public String policy() {
        return POLICY;
    }

    @Override
    public Exchange open(Call call) {
        return new MaskingExchange();
    }

    private static final class MaskingExchange implements Exchange {
        private final Pseudonymiser pseudonymiser = new Pseudonymiser();

        @Override
        public Prompt beforeSend(Prompt prompt) {
            List<Message> messages = prompt.getInstructions();
            messages.forEach(m -> pseudonymiser.reserve(m.getText()));
            List<Message> masked = new ArrayList<>(messages.size());
            for (Message message : messages) masked.add(mask(message));
            return new Prompt(masked, prompt.getOptions());
        }

        private Message mask(Message message) {
            return switch (message) {
                case SystemMessage s -> s.mutate().text(pseudonymiser.mask(s.getText())).build();
                case UserMessage u -> u.mutate().text(pseudonymiser.mask(u.getText())).build();
                case AssistantMessage a when !a.hasToolCalls() ->
                        a.mutate().content(pseudonymiser.mask(a.getText())).build();
                // Tool calls and tool responses carry structured text this filter cannot rewrite safely; the gateway
                // sends neither today. Refusing is the fail-closed answer.
                default -> throw new IllegalStateException("PII masking cannot rewrite this message kind: "
                        + message.getMessageType());
            };
        }

        @Override
        public ChatResponse afterReceive(ChatResponse response) {
            if (response == null || !pseudonymiser.masked()) return response;
            List<Generation> restored = new ArrayList<>(response.getResults().size());
            for (Generation g : response.getResults()) {
                restored.add(withText(g, pseudonymiser.restore(g.getOutput().getText())));
            }
            return new ChatResponse(restored, response.getMetadata());
        }

        @Override
        public Flux<ChatResponse> afterReceive(Flux<ChatResponse> stream) {
            if (!pseudonymiser.masked()) return stream;
            // One restorer per generation index (providers stream one; several are kept apart all the same).
            Map<Integer, Pseudonymiser.Restorer> restorers = new java.util.HashMap<>();
            return stream.map(chunk -> {
                List<Generation> out = new ArrayList<>(chunk.getResults().size());
                for (int i = 0; i < chunk.getResults().size(); i++) {
                    Generation g = chunk.getResults().get(i);
                    String text = g.getOutput() == null ? null : g.getOutput().getText();
                    out.add(text == null ? g
                            : withText(g, restorers.computeIfAbsent(i, k -> pseudonymiser.restorer()).push(text)));
                }
                return new ChatResponse(out, chunk.getMetadata());
            }).concatWith(Flux.defer(() -> {
                // The held-back tail of each generation, as one last chunk without metadata (no usage of its own).
                List<Generation> rest = new ArrayList<>();
                int size = restorers.keySet().stream().mapToInt(i -> i + 1).max().orElse(0);
                boolean any = false;
                for (int i = 0; i < size; i++) {
                    Pseudonymiser.Restorer r = restorers.get(i);
                    String text = r == null ? "" : r.flush();
                    any |= !text.isEmpty();
                    rest.add(new Generation(new AssistantMessage(text)));
                }
                return any ? Flux.just(new ChatResponse(rest)) : Flux.empty();
            }));
        }

        @Override
        public Filtered filtered() {
            return new Filtered(POLICY, pseudonymiser.counts());
        }

        private static Generation withText(Generation g, String text) {
            return new Generation(g.getOutput().mutate().content(text).build(), g.getMetadata());
        }
    }
}
