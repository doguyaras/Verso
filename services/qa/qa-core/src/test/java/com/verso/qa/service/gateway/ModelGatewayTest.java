package com.verso.qa.service.gateway;
import com.verso.qa.repository.ModelConnection;
import com.verso.qa.repository.ModelConnectionRepository;
import com.verso.qa.repository.ModelGatewayRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.verso.platform.security.authority.CallerGroups;
import com.verso.qa.api.dto.ModelPrice;
import com.verso.qa.api.dto.ModelProviderType;
import com.verso.qa.api.dto.ModelRouteEntry;
import com.verso.qa.api.dto.ModelTask;
import com.verso.qa.config.AiMode;
import com.verso.qa.config.QaGatewayProperties;
import com.verso.qa.config.QaProperties;
import com.verso.qa.config.UsageMetricsProperties;
import com.verso.qa.config.VersoAiProperties;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.service.QaAudit;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

/** ADR-0056: route resolution, the strict mode at call time, budgets, cost, team attribution and user choices. */
class ModelGatewayTest {

    private final ModelConnectionRepository connections = mock(ModelConnectionRepository.class);
    private final ModelGatewayRepository repository = mock(ModelGatewayRepository.class);
    private final QaAudit audit = mock(QaAudit.class);
    private final CallerGroups groups = mock(CallerGroups.class);
    private final ChatModel builtIn = mock(ChatModel.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-15T10:00:00Z"), ZoneOffset.UTC);

    private final ModelConnection cloud = connection(ModelProviderType.ANTHROPIC, ModelConnection.Locality.EXTERNAL);
    private final ModelConnection local = connection(ModelProviderType.OPENAI_COMPATIBLE, ModelConnection.Locality.LOCAL);

    static ModelConnection connection(ModelProviderType type, ModelConnection.Locality locality) {
        return new ModelConnection(UUID.randomUUID(), type.name(), type, null, null, null, null, List.of("m1", "m2"),
                true, locality, "h", null, null, 0, null, null, null, Instant.EPOCH, Instant.EPOCH, 3);
    }

    @SuppressWarnings("unchecked")
    private ModelGateway gateway(AiMode mode) {
        when(connections.list()).thenReturn(List.of(cloud, local));
        when(groups.current()).thenReturn(List.of("satis", "finans"));
        QaProperties qa = new QaProperties(5, 0.47, 0.44, 0.045, 0.50, 1500, 1, Duration.ofMillis(300), 2,
                Duration.ofSeconds(15), Duration.ofSeconds(2), Duration.ofSeconds(2));
        return new ModelGateway(connections, repository, builtIn, new VersoAiProperties(mode, "builtin-chat", "e"), qa,
                new QaGatewayProperties(Duration.ofSeconds(5), Duration.ofSeconds(10), 5, 13),
                new UsageMetricsProperties("Europe/Istanbul", false, false, 3, 20, 366),
                LocalKeyEncryptionKey.ofSecret("q".repeat(40)), audit, groups, clock, new SimpleMeterRegistry(),
                mock(ObjectProvider.class), mock(ObjectProvider.class));
    }

    /** The gateway with its provider clients replaced by {@code clients} (by model name); everything else is real. */
    @SuppressWarnings("unchecked")
    private ModelGateway gatewayWith(Map<String, ChatModel> clients) {
        when(connections.list()).thenReturn(List.of(cloud, local));
        when(groups.current()).thenReturn(List.of("satis"));
        QaProperties qa = new QaProperties(5, 0.47, 0.44, 0.045, 0.50, 1500, 1, Duration.ofMillis(300), 2,
                Duration.ofSeconds(15), Duration.ofSeconds(2), Duration.ofSeconds(2));
        return new ModelGateway(connections, repository, builtIn, new VersoAiProperties(AiMode.CLOUD, "builtin-chat",
                "e"), qa, new QaGatewayProperties(Duration.ofSeconds(5), Duration.ofSeconds(10), 5, 13),
                new UsageMetricsProperties("Europe/Istanbul", false, false, 3, 20, 366),
                LocalKeyEncryptionKey.ofSecret("q".repeat(40)), audit, groups, clock, new SimpleMeterRegistry(),
                mock(ObjectProvider.class), mock(ObjectProvider.class)) {
            @Override
            public ChatModel client(RoutedChatModel.Candidate candidate) {
                return clients.get(candidate.model());
            }
        };
    }

    private static final String RAW_PROMPT = "Başvuru sahibi: TC 10000000146, IBAN TR33 0006 1005 1978 6457 8413 26, "
            + "telefon 0532 123 45 67, e-posta ayse.yilmaz@ornek.com.tr, araç 34 ABC 123, kart 4111 1111 1111 1111, "
            + "hatalı vergi no 1234567891, doğrusu VKN 4840847211. Sipariş 4821-77 ve 15.01.2024 tarihli talep.";

    @Test
    void cloudCall_sendsNoRawValue_restoresTheAnswer_andRecordsCountsOnly_whileALocalCallIsUnchanged() {
        when(repository.piiMasking()).thenReturn(true);
        routes(new ModelRouteEntry(cloud.id(), "m1"));
        RoutedChatModelTest.EchoModel cloudClient = new RoutedChatModelTest.EchoModel("Kayıt [TCKN_1], [PHONE_1] ve ",
                "[IBAN_1] ile eşleşti.");
        RoutedChatModelTest.EchoModel localClient = new RoutedChatModelTest.EchoModel("yerel");
        ModelGateway gateway = gatewayWith(Map.of("m1", cloudClient, "m2", localClient));
        org.mockito.ArgumentCaptor<ModelGatewayRepository.Usage> usage =
                org.mockito.ArgumentCaptor.forClass(ModelGatewayRepository.Usage.class);

        String answer = gateway.route(ModelTask.ANSWER, null).call(new org.springframework.ai.chat.prompt.Prompt(
                List.of(new org.springframework.ai.chat.messages.SystemMessage("Sen bir asistansın."),
                        new org.springframework.ai.chat.messages.UserMessage(RAW_PROMPT)))).getResult().getOutput()
                .getText();

        String sent = cloudClient.sent.getFirst();
        assertThat(sent).doesNotContain("10000000146", "TR33 0006 1005 1978 6457 8413 26", "0532 123 45 67",
                "ayse.yilmaz@ornek.com.tr", "34 ABC 123", "4111 1111 1111 1111", "4840847211");
        assertThat(sent).contains("[TCKN_1]", "[IBAN_1]", "[PHONE_1]", "[EMAIL_1]", "[PLATE_1]", "[CARD_1]",
                "[VKN_1]", "vergi no 1234567891", "Sipariş 4821-77", "15.01.2024", "Sen bir asistansın.");
        assertThat(answer).isEqualTo("Kayıt 10000000146, 0532 123 45 67 ve TR33 0006 1005 1978 6457 8413 26 ile "
                + "eşleşti.");
        verify(repository).insertUsage(usage.capture());
        assertThat(usage.getValue().filterPolicy()).isEqualTo("PII_PSEUDONYMISE");
        assertThat(usage.getValue().maskedCounts()).containsExactlyInAnyOrderEntriesOf(Map.of("TCKN", 1, "IBAN", 1,
                "PHONE", 1, "EMAIL", 1, "PLATE", 1, "CARD", 1, "VKN", 1));
        assertThat(usage.getValue().toString()).doesNotContain("10000000146", "ayse.yilmaz");

        // The same prompt to a local connection leaves it exactly as it was, and nothing is counted as masked.
        routes(new ModelRouteEntry(local.id(), "m2"));
        gateway.invalidate();
        gateway.route(ModelTask.ANSWER, null).call(new org.springframework.ai.chat.prompt.Prompt(
                new org.springframework.ai.chat.messages.UserMessage(RAW_PROMPT)));
        assertThat(localClient.sent).containsExactly(RAW_PROMPT);
        verify(repository, times(2)).insertUsage(usage.capture());
        assertThat(usage.getValue().filterPolicy()).isEqualTo("NONE");
        assertThat(usage.getValue().maskedCounts()).isEmpty();
    }

    @Test
    void cloudCall_withMaskingTurnedOff_isSentAsIs_andSaysSo() {
        when(repository.piiMasking()).thenReturn(false);
        routes(new ModelRouteEntry(cloud.id(), "m1"));
        RoutedChatModelTest.EchoModel cloudClient = new RoutedChatModelTest.EchoModel("tamam");
        ModelGateway gateway = gatewayWith(Map.of("m1", cloudClient));
        org.mockito.ArgumentCaptor<ModelGatewayRepository.Usage> usage =
                org.mockito.ArgumentCaptor.forClass(ModelGatewayRepository.Usage.class);

        gateway.route(ModelTask.ANSWER, null).call(new org.springframework.ai.chat.prompt.Prompt(
                new org.springframework.ai.chat.messages.UserMessage(RAW_PROMPT)));

        assertThat(cloudClient.sent).containsExactly(RAW_PROMPT);
        verify(repository).insertUsage(usage.capture());
        assertThat(usage.getValue().filterPolicy()).isEqualTo("PASS_THROUGH");
        assertThat(usage.getValue().maskedCounts()).isEmpty();
    }

    private static final String NAMED_PROMPT = "Başvuru sahibi Sn. Ayşe Yılmaz, adres: Kızılay Mah. Atatürk Bulvarı "
            + "No:45 D:7 Çankaya/Ankara. Ayşe Yılmaz'ın yöneticisi Mehmet Öztürk, TC 10000000146. Hacettepe "
            + "Üniversitesi ve 4857 sayılı İş Kanunu kapsamında, Ankara ofisinde.";

    /**
     * ADR-0065 and the ADR-0059 review: in cloud mode the built-in model is Anthropic or OpenAI, and it is the route
     * when nothing is routed (or the database cannot be read). Its prompt is masked like an external connection's:
     * no raw name, address or TCKN; the answer gets them back; the usage row says so.
     */
    @Test
    void cloudMode_builtInModel_isMasked_namesAndAddressesToo_andRecordedAsExternal() {
        when(repository.piiMasking()).thenReturn(true);
        RoutedChatModelTest.EchoModel builtInClient = new RoutedChatModelTest.EchoModel(
                "[AD_1]'in talebi [AD_2]'e iletildi; adres [ADRES_1]'de.");
        ModelGateway gateway = gatewayWith(Map.of("builtin-chat", builtInClient));
        org.mockito.ArgumentCaptor<ModelGatewayRepository.Usage> usage =
                org.mockito.ArgumentCaptor.forClass(ModelGatewayRepository.Usage.class);

        RoutedChatModel route = (RoutedChatModel) gateway.route(ModelTask.ANSWER, null);
        assertThat(route.candidates()).singleElement().satisfies(c -> {
            assertThat(c.connection()).isNull();
            assertThat(c.external()).isTrue();
        });
        String answer = route.call(new org.springframework.ai.chat.prompt.Prompt(
                new org.springframework.ai.chat.messages.UserMessage(NAMED_PROMPT))).getResult().getOutput().getText();

        String sent = builtInClient.sent.getFirst();
        assertThat(sent).doesNotContain("Ayşe", "Yılmaz", "Öztürk", "Kızılay", "Atatürk Bulvarı", "Çankaya",
                "10000000146");
        assertThat(sent).contains("Sn. [AD_1], adres: [ADRES_1]. [AD_1]'ın yöneticisi [AD_2], TC [TCKN_1]",
                "Hacettepe Üniversitesi ve 4857 sayılı İş Kanunu kapsamında, Ankara ofisinde.");
        assertThat(answer).isEqualTo("Ayşe Yılmaz'ın talebi Mehmet Öztürk'e iletildi; adres Kızılay Mah. Atatürk "
                + "Bulvarı No:45 D:7 Çankaya/Ankara'da.");
        verify(repository).insertUsage(usage.capture());
        assertThat(usage.getValue().external()).isTrue();
        assertThat(usage.getValue().connectionId()).isNull();
        assertThat(usage.getValue().filterPolicy()).isEqualTo("PII_PSEUDONYMISE");
        assertThat(usage.getValue().sentCategories()).containsExactly("SYSTEM_INSTRUCTIONS", "QUESTION", "PASSAGES");
        assertThat(usage.getValue().maskedCounts()).containsExactlyInAnyOrderEntriesOf(Map.of("AD", 2, "ADRES", 1,
                "TCKN", 1));
        assertThat(usage.getValue().toString()).doesNotContain("Ayşe", "Kızılay");
    }

    @Test
    void localMode_builtInModel_isNotExternal_andNeverFiltered() {
        RoutedChatModel route = (RoutedChatModel) gateway(AiMode.LOCAL).route(ModelTask.ANSWER, null);

        assertThat(route.candidates()).singleElement().satisfies(c -> assertThat(c.external()).isFalse());
    }

    private void routes(ModelRouteEntry... entries) {
        when(repository.routes()).thenReturn(Map.of(ModelTask.ANSWER, List.of(entries)));
    }

    @Test
    void route_withoutEntries_isTheBuiltInModel() {
        when(repository.routes()).thenReturn(Map.of());
        RoutedChatModel route = (RoutedChatModel) gateway(AiMode.CLOUD).route(ModelTask.ANSWER, null);

        assertThat(route.candidates()).singleElement().satisfies(c -> {
            assertThat(c.connection()).isNull();
            assertThat(c.model()).isEqualTo("builtin-chat");
        });
    }

    @Test
    void route_inLocalMode_dropsCloudConnections_evenWhenTheDatabaseRoutesThem() {
        routes(new ModelRouteEntry(cloud.id(), "m1"), new ModelRouteEntry(local.id(), "m2"));

        RoutedChatModel cloudMode = (RoutedChatModel) gateway(AiMode.CLOUD).route(ModelTask.ANSWER, null);
        RoutedChatModel localMode = (RoutedChatModel) gateway(AiMode.LOCAL).route(ModelTask.ANSWER, null);

        assertThat(cloudMode.candidates()).extracting(RoutedChatModel.Candidate::model).containsExactly("m1", "m2");
        assertThat(localMode.candidates()).extracting(c -> c.connection().id()).containsExactly(local.id());
        assertThatThrownBy(() -> gateway(AiMode.CLOUD).route(ModelTask.EMBEDDING, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void choices_onlyWhenAllowed_andTheChosenModelGoesFirst() {
        routes(new ModelRouteEntry(cloud.id(), "m1"), new ModelRouteEntry(local.id(), "m2"));
        ModelGateway off = gateway(AiMode.CLOUD);
        assertThat(off.choices().enabled()).isFalse();
        assertThatThrownBy(() -> off.validChoice(local.id() + "/m2")).isInstanceOf(QaServiceException.class)
                .extracting(e -> ((QaServiceException) e).getErrorCode()).isEqualTo(QaErrorCode.MODEL_CHOICE_INVALID);

        when(repository.userModelChoice()).thenReturn(true);
        ModelGateway on = gateway(AiMode.CLOUD);
        assertThat(on.choices().choices()).extracting(c -> c.external()).containsExactly(true, false);
        String chosen = on.validChoice(local.id() + "/m2");
        assertThat(((RoutedChatModel) on.route(ModelTask.ANSWER, chosen)).candidates())
                .extracting(RoutedChatModel.Candidate::model).containsExactly("m2", "m1");
        assertThatThrownBy(() -> on.validChoice(UUID.randomUUID() + "/m1")).isInstanceOf(QaServiceException.class);
    }

    @Test
    void overBudget_blocksOnlyPricedModels_atTheHardLimit_ofTheConnectionOrTheTeam() {
        routes(new ModelRouteEntry(cloud.id(), "m1"));
        when(repository.prices()).thenReturn(List.of(new ModelPrice(cloud.id(), "m1", BigDecimal.ONE, BigDecimal.TEN,
                "EUR")));
        when(repository.budgets()).thenReturn(List.of(
                new ModelGatewayRepository.BudgetRow("CONNECTION", cloud.id().toString(), new BigDecimal("10.00"),
                        new BigDecimal("8.00"), null, null),
                new ModelGatewayRepository.BudgetRow("TEAM", "satis", new BigDecimal("5.00"), null, null, null)));
        ModelGateway gateway = gateway(AiMode.CLOUD);
        RoutedChatModel route = (RoutedChatModel) gateway.route(ModelTask.ANSWER, null);
        RoutedChatModel.Candidate paid = route.candidates().getFirst();

        when(repository.spentByConnection("2026-10", cloud.id())).thenReturn(new BigDecimal("9.99"));
        when(repository.spentByTeam("2026-10", "satis")).thenReturn(new BigDecimal("4.99"));
        assertThat(gateway.overBudget(paid, "satis")).isFalse();

        when(repository.spentByConnection("2026-10", cloud.id())).thenReturn(new BigDecimal("10.00"));
        assertThat(gateway.overBudget(paid, "satis")).as("connection at its hard limit").isTrue();

        when(repository.spentByConnection("2026-10", cloud.id())).thenReturn(BigDecimal.ONE);
        when(repository.spentByTeam("2026-10", "satis")).thenReturn(new BigDecimal("5.00"));
        assertThat(gateway.overBudget(paid, "satis")).as("team at its hard limit").isTrue();
        assertThat(gateway.overBudget(paid, "finans")).as("another team").isFalse();

        assertThat(gateway.overBudget(new RoutedChatModel.Candidate(null, "builtin-chat"), "satis"))
                .as("the built-in model is never blocked").isFalse();
        assertThat(gateway.overBudget(new RoutedChatModel.Candidate(local, "m2"), "satis"))
                .as("an unpriced model is never blocked").isFalse();
    }

    @Test
    void team_isTheFirstGroupWithABudget_elseTheFirstGroup() {
        ModelGateway.Snapshot snapshot = new ModelGateway.Snapshot(Map.of(), Map.of(), Map.of(),
                Map.of("TEAM/satis", new BigDecimal[]{BigDecimal.ONE, null}), false, true, Instant.EPOCH);

        assertThat(ModelGateway.team(snapshot, List.of("satis", "finans"))).isEqualTo("satis");
        assertThat(ModelGateway.team(snapshot, List.of("ik", "finans"))).isEqualTo("finans");
        assertThat(ModelGateway.team(snapshot, List.of())).isNull();
    }

    @Test
    void record_costsTokensAtTheAdminsPrice_andReportsEachLimitOncePerMonth() {
        routes(new ModelRouteEntry(cloud.id(), "m1"));
        when(repository.prices()).thenReturn(List.of(new ModelPrice(cloud.id(), "m1", new BigDecimal("3"),
                new BigDecimal("15"), "USD")));
        when(repository.budgets()).thenReturn(List.of(new ModelGatewayRepository.BudgetRow("CONNECTION",
                cloud.id().toString(), new BigDecimal("100"), new BigDecimal("1"), null, null)));
        when(repository.spentByConnection(anyString(), any())).thenReturn(new BigDecimal("2"));
        when(repository.markNotified(eq("CONNECTION"), anyString(), eq("SOFT"), eq("2026-10"))).thenReturn(true, false);
        ModelGateway gateway = gateway(AiMode.CLOUD);
        RoutedChatModel.Candidate paid = ((RoutedChatModel) gateway.route(ModelTask.ANSWER, null)).candidates().getFirst();

        gateway.record(paid, ModelTask.ANSWER, null, "satis", "acct", "OK", new DefaultUsage(1000, 200), List.of(
                ModelRequestFilter.EgressCategory.QUESTION), ModelRequestFilter.Filtered.NONE);
        gateway.record(paid, ModelTask.ANSWER, null, "satis", "acct", "OK", new DefaultUsage(1000, 200), List.of(),
                ModelRequestFilter.Filtered.NONE);

        // 1000 × 3 / 1M + 200 × 15 / 1M = 0.006
        assertThat(ModelGateway.cost(new ModelPrice(cloud.id(), "m1", new BigDecimal("3"), new BigDecimal("15"), "USD"),
                1000, 200)).isEqualByComparingTo("0.006");
        verify(repository, times(2)).insertUsage(any());
        verify(repository, times(2)).reach(eq("2026-10"), eq("satis"), any());
        verify(audit, times(1)).modelBudgetLimitReached(cloud.id(), "CONNECTION", "SOFT");
        verify(audit, never()).modelBudgetLimitReached(any(), eq("CONNECTION"), eq("HARD"));
    }
}
