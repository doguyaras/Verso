package com.verso.qa.service.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/** ADR-0059: stable per-call placeholders, the round trip, and restoring a stream split anywhere. */
class PseudonymiserTest {

    private static final String TEXT = "Ayşe Hanım (TC 10000000146) IBAN TR33 0006 1005 1978 6457 8413 26 hesabına, "
            + "TC 12345678950 ise 0532 123 45 67 numarasından aradı. Tekrar: 10000000146 ve "
            + "TR330006100519786457841326.";

    @Test
    void mask_sameValueSamePlaceholder_numberedPerType_andNoRawValueLeft() {
        Pseudonymiser p = new Pseudonymiser();

        String masked = p.mask(TEXT);

        assertThat(masked).isEqualTo("[AD_1] Hanım (TC [TCKN_1]) IBAN [IBAN_1] hesabına, TC [TCKN_2] ise [PHONE_1] "
                + "numarasından aradı. Tekrar: [TCKN_1] ve [IBAN_1].");
        assertThat(p.counts()).containsExactlyInAnyOrderEntriesOf(Map.of("TCKN", 2, "IBAN", 1, "PHONE", 1, "AD", 1));
        assertThat(p.counts().toString()).doesNotContain("10000000146");
    }

    @Test
    void placeholders_stayStableAcrossTheMessagesOfOneCall_butNotAcrossCalls() {
        Pseudonymiser call = new Pseudonymiser();
        assertThat(call.mask("TC 12345678950")).isEqualTo("TC [TCKN_1]");
        assertThat(call.mask("önceki tur: 10000000146 ve 12345678950")).isEqualTo("önceki tur: [TCKN_2] ve [TCKN_1]");

        Pseudonymiser next = new Pseudonymiser();
        assertThat(next.mask("10000000146")).as("a new call starts over").isEqualTo("[TCKN_1]");
        assertThat(next.restore("[TCKN_2]")).as("another call's placeholder means nothing here").isEqualTo("[TCKN_2]");
    }

    @Test
    void roundTrip_restoresTheFirstSpelling_andLeavesOtherBracketsAlone() {
        Pseudonymiser p = new Pseudonymiser();
        p.mask(TEXT);

        assertThat(p.restore("[TCKN_1] numaralı müşterinin [IBAN_1] hesabı [1] ve [2] kaynaklarında; [TCKN_9] yok."))
                .isEqualTo("10000000146 numaralı müşterinin TR33 0006 1005 1978 6457 8413 26 hesabı [1] ve [2] "
                        + "kaynaklarında; [TCKN_9] yok.");
        assertThat(p.restore(p.mask(TEXT))).isEqualTo(TEXT.replace("TR330006100519786457841326",
                "TR33 0006 1005 1978 6457 8413 26"));
    }

    @Test
    void placeholderShapedTextAlreadyInThePrompt_isNeverTurnedIntoAValue() {
        Pseudonymiser p = new Pseudonymiser();
        String quoted = "Şablondaki [TCKN_1] alanına müşterinin numarası yazılır.";
        p.reserve(quoted);
        p.reserve("TC 10000000146");

        assertThat(p.mask(quoted)).isEqualTo(quoted);
        assertThat(p.mask("TC 10000000146")).isEqualTo("TC [TCKN_2]");
        assertThat(p.restore("[TCKN_1] alanı ve [TCKN_2]")).isEqualTo("[TCKN_1] alanı ve 10000000146");
    }

    @Test
    void nothingToMask_isTheSameText() {
        Pseudonymiser p = new Pseudonymiser();
        String plain = "Yıllık izin yirmi iş günüdür [1].";

        assertThat(p.mask(plain)).isSameAs(plain);
        assertThat(p.restore(plain)).isSameAs(plain);
        assertThat(p.counts()).isEmpty();
        assertThat(p.masked()).isFalse();
    }

    @Test
    void stream_restoresAPlaceholderSplitAtEveryPossiblePosition() {
        Pseudonymiser p = new Pseudonymiser();
        p.mask(TEXT);
        String answer = "Müşteri [TCKN_1], hesap [IBAN_1], telefon [PHONE_1] [1].";
        String expected = p.restore(answer);

        for (int i = 0; i <= answer.length(); i++) {
            for (int j = i; j <= answer.length(); j++) {
                Pseudonymiser.Restorer r = p.restorer();
                String out = r.push(answer.substring(0, i)) + r.push(answer.substring(i, j))
                        + r.push(answer.substring(j)) + r.flush();
                assertThat(out).as("split at %d and %d", i, j).isEqualTo(expected);
            }
        }
    }

    @Test
    void stream_tokenByToken_neverEmitsAPartialPlaceholder() {
        Pseudonymiser p = new Pseudonymiser();
        p.mask("TC 10000000146");
        Pseudonymiser.Restorer r = p.restorer();
        List<String> emitted = new ArrayList<>();
        for (char c : "Numara [TCKN_1] doğru.".toCharArray()) emitted.add(r.push(String.valueOf(c)));
        emitted.add(r.flush());

        assertThat(String.join("", emitted)).isEqualTo("Numara 10000000146 doğru.");
        assertThat(emitted).noneMatch(s -> s.contains("[T") || s.contains("KN_"));
    }

    @Test
    void stream_releasesAnUnfinishedTailAtTheEnd_andDoesNotHoldCitations() {
        Pseudonymiser p = new Pseudonymiser();
        p.mask("TC 10000000146");
        Pseudonymiser.Restorer r = p.restorer();

        assertThat(r.push("kaynak [1")).as("a citation is not a placeholder prefix").isEqualTo("kaynak [1");
        assertThat(r.push("] ve [TCK")).isEqualTo("] ve ");
        assertThat(r.flush()).isEqualTo("[TCK");
        assertThat(Pseudonymiser.couldBecomePlaceholder("")).isTrue();
        assertThat(Pseudonymiser.couldBecomePlaceholder("PHO")).isTrue();
        assertThat(Pseudonymiser.couldBecomePlaceholder("IBAN_12")).isTrue();
        assertThat(Pseudonymiser.couldBecomePlaceholder("IBAN_1x")).isFalse();
        assertThat(Pseudonymiser.couldBecomePlaceholder("NOTE")).isFalse();
    }

    @Test
    void placeholderShapedTextAtTheTopOfTheRange_neverPushesTheNumbersPastSixDigits() {
        Pseudonymiser p = new Pseudonymiser();
        String quoted = "Şablon [TCKN_999999] ve [TCKN_1] alanları.";
        p.reserve(quoted);
        p.reserve("TC 10000000146");

        assertThat(p.mask("TC 10000000146")).isEqualTo("TC [TCKN_2]");
        assertThat(p.restore("[TCKN_2], [TCKN_1], [TCKN_999999]")).isEqualTo("10000000146, [TCKN_1], [TCKN_999999]");
    }

    // ------------------------------------------------------------------------------------------------ names (ADR-0065)

    private static final String HR = "Sn. Ayşe Yılmaz izin talebini iletti. Ayşe Yılmaz'ın yöneticisi Mehmet Öztürk, "
            + "AYŞE YILMAZ adına onay verdi. Adres: Kızılay Mah. Atatürk Cad. No:12 D:5 Çankaya/Ankara'dır.";

    @Test
    void names_andAddresses_sameValueSamePlaceholder_acrossSuffixesAndCase() {
        Pseudonymiser p = new Pseudonymiser();

        String masked = p.mask(HR);

        assertThat(masked).isEqualTo("Sn. [AD_1] izin talebini iletti. [AD_1]'ın yöneticisi [AD_2], [AD_1] adına onay "
                + "verdi. Adres: [ADRES_1]'dır.");
        assertThat(masked).doesNotContain("Ayşe", "Yılmaz", "YILMAZ", "Öztürk", "Kızılay", "Atatürk", "Çankaya");
        assertThat(p.counts()).containsExactlyInAnyOrderEntriesOf(Map.of("AD", 2, "ADRES", 1));
        assertThat(p.restore(masked)).as("round trip: the first spelling comes back")
                .isEqualTo(HR.replace("AYŞE YILMAZ", "Ayşe Yılmaz"));
    }

    @Test
    void restore_fitsTheSuffixTheModelWrote_toTheValue() {
        Pseudonymiser p = new Pseudonymiser();
        p.mask("Ayşe Yılmaz ve Ahmet Kaya; Ayşe; Atatürk Cad. No:12 Çankaya/Ankara");

        assertThat(p.restore("[AD_1]'in dosyası [AD_2]'e iletildi; [AD_3]'a da bilgi verildi, [ADRES_1]'de."))
                .isEqualTo("Ayşe Yılmaz'ın dosyası Ahmet Kaya'ya iletildi; Ayşe'ye da bilgi verildi, "
                        + "Atatürk Cad. No:12 Çankaya/Ankara'da.");
        assertThat(p.restore("[AD_1]’nin")).as("typographic apostrophe").isEqualTo("Ayşe Yılmaz’ın");
        assertThat(p.restore("[AD_1]'ın")).as("a correct suffix stays").isEqualTo("Ayşe Yılmaz'ın");
    }

    @Test
    void stream_restoresAndFitsANameSuffix_splitAtEveryPossiblePosition() {
        Pseudonymiser p = new Pseudonymiser();
        p.mask(HR);
        String answer = "[AD_1]'in talebi [AD_2]'e gitti; adres [ADRES_1]'de [1]. [AD_1]";
        String expected = p.restore(answer);
        assertThat(expected).isEqualTo("Ayşe Yılmaz'ın talebi Mehmet Öztürk'e gitti; adres Kızılay Mah. Atatürk Cad. "
                + "No:12 D:5 Çankaya/Ankara'da [1]. Ayşe Yılmaz");

        for (int i = 0; i <= answer.length(); i++) {
            for (int j = i; j <= answer.length(); j++) {
                Pseudonymiser.Restorer r = p.restorer();
                String out = r.push(answer.substring(0, i)) + r.push(answer.substring(i, j))
                        + r.push(answer.substring(j)) + r.flush();
                assertThat(out).as("split at %d and %d", i, j).isEqualTo(expected);
            }
        }
    }

    @Test
    void stream_tokenByToken_neverEmitsAPartialNamePlaceholderOrAnUnfittedSuffix() {
        Pseudonymiser p = new Pseudonymiser();
        p.mask("Ayşe Yılmaz");
        Pseudonymiser.Restorer r = p.restorer();
        List<String> emitted = new ArrayList<>();
        for (char c : "Dosya [AD_1]'in.".toCharArray()) emitted.add(r.push(String.valueOf(c)));
        emitted.add(r.flush());

        assertThat(String.join("", emitted)).isEqualTo("Dosya Ayşe Yılmaz'ın.");
        assertThat(emitted).noneMatch(s -> s.contains("[A") || s.contains("D_") || s.endsWith("'i"));
        assertThat(Pseudonymiser.couldBecomePlaceholder("ADR")).isTrue();
        assertThat(Pseudonymiser.couldBecomePlaceholder("ADRES_12")).isTrue();
        assertThat(Pseudonymiser.holdFrom("x [AD_1]'in")).isEqualTo(2);
        assertThat(Pseudonymiser.holdFrom("x [AD_1]'in ")).isEqualTo(12);
        assertThat(Pseudonymiser.holdFrom("x [TCKN_1]")).as("only names and addresses wait for a suffix").isEqualTo(10);
    }

    // ------------------------------------------------------------------------------------------------ filter

    @Test
    void filter_masksEveryTextMessage_keepsTheOptions_andRestoresAWholeAnswer() {
        ModelRequestFilter.Exchange exchange = new PiiMaskingFilter().open(null);
        Prompt prompt = new Prompt(List.of(new SystemMessage("Kurallar: kişisel veriyi tekrar etme."),
                new UserMessage("Önceki soru: ayse.yilmaz@ornek.com.tr adresi kimin?"),
                new AssistantMessage("[1] belgesine göre ayse.yilmaz@ornek.com.tr Ayşe Hanım'ındır."),
                new UserMessage("Peki 0532 123 45 67 kimin?")));

        Prompt sent = exchange.beforeSend(prompt);
        ChatResponse back = exchange.afterReceive(new ChatResponse(List.of(new Generation(
                new AssistantMessage("[PHONE_1] numarası da [EMAIL_1] sahibinindir [1].")))));

        assertThat(sent.getInstructions()).hasSize(4);
        assertThat(sent.getContents()).doesNotContain("ayse.yilmaz", "0532 123 45 67")
                .contains("[EMAIL_1] adresi", "göre [EMAIL_1] [AD_1] Hanım'ındır", "Peki [PHONE_1] kimin");
        assertThat(sent.getInstructions().get(2)).isInstanceOf(AssistantMessage.class);
        assertThat(back.getResult().getOutput().getText())
                .isEqualTo("0532 123 45 67 numarası da ayse.yilmaz@ornek.com.tr sahibinindir [1].");
        assertThat(exchange.filtered().masked()).containsExactlyInAnyOrderEntriesOf(Map.of("EMAIL", 1, "PHONE", 1,
                "AD", 1));
    }

    @Test
    void filter_stream_emitsTheHeldTailAsOneLastChunk() {
        ModelRequestFilter.Exchange exchange = new PiiMaskingFilter().open(null);
        exchange.beforeSend(new Prompt(new UserMessage("Plaka 34 ABC 123")));

        List<String> chunks = exchange.afterReceive(Flux.just(chunk("Araç [PLA"), chunk("TE_1] ve ["))).map(
                r -> r.getResult().getOutput().getText()).collectList().block();

        assertThat(chunks).containsExactly("Araç ", "34 ABC 123 ve ", "[");
    }

    @Test
    void filter_withNothingMasked_passesTheStreamThroughUntouched() {
        ModelRequestFilter.Exchange exchange = new PiiMaskingFilter().open(null);
        exchange.beforeSend(new Prompt(new UserMessage("Yıllık izin kaç gün?")));
        Flux<ChatResponse> stream = Flux.just(chunk("[TCKN_1] ["));

        assertThat(exchange.afterReceive(stream)).isSameAs(stream);
        assertThat(exchange.filtered().masked()).isEmpty();
        assertThat(exchange.filtered().policy()).isEqualTo("PII_PSEUDONYMISE");
    }

    private static ChatResponse chunk(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
