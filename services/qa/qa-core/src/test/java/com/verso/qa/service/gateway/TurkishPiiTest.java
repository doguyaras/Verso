package com.verso.qa.service.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.service.gateway.TurkishPii.Type;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ADR-0059: every detector with valid and invalid check digits, the common Turkish spellings, the look-alikes that
 * must stay readable (order numbers, dates, amounts, units) and the Turkish text around the values. All values are
 * synthetic: they satisfy the published algorithms and belong to no one.
 */
class TurkishPiiTest {

    private static List<String> found(String text, Type type) {
        return TurkishPii.find(text).stream().filter(m -> m.type() == type)
                .map(m -> text.substring(m.start(), m.end())).toList();
    }

    private static List<Type> types(String text) {
        return TurkishPii.find(text).stream().map(TurkishPii.Match::type).toList();
    }

    // ------------------------------------------------------------------------------------------------ TCKN

    @Test
    void tckn_checkDigits() {
        assertThat(TurkishPii.validTckn("10000000146")).isTrue();
        assertThat(TurkishPii.validTckn("12345678950")).isTrue();
        assertThat(TurkishPii.validTckn("28471930510")).isTrue();
        assertThat(TurkishPii.validTckn("10000000147")).as("11th digit wrong").isFalse();
        assertThat(TurkishPii.validTckn("10000000136")).as("10th digit wrong").isFalse();
        assertThat(TurkishPii.validTckn("12345678901")).isFalse();
        assertThat(TurkishPii.validTckn("02345678950")).as("never starts with 0").isFalse();
        assertThat(TurkishPii.validTckn("1000000014")).as("ten digits").isFalse();
    }

    @Test
    void tckn_inTurkishText_withSuffixesAndPunctuation() {
        assertThat(found("Müşterinin T.C. kimlik numarası 10000000146'dır.", Type.TCKN)).containsExactly("10000000146");
        assertThat(found("TCKN:12345678950, doğum yeri İzmir", Type.TCKN)).containsExactly("12345678950");
        assertThat(found("(28471930510) numaralı başvuru", Type.TCKN)).containsExactly("28471930510");
    }

    @Test
    void tckn_lookAlikes_stayReadable() {
        assertThat(found("Sipariş numarası 12345678901 kargoya verildi.", Type.TCKN)).isEmpty();
        assertThat(found("Fatura tutarı 10000000147 kuruş", Type.TCKN)).isEmpty();
        assertThat(found("Seri no 100000001460 ve 9100000000146", Type.TCKN)).as("inside longer numbers").isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ VKN

    @Test
    void vkn_checkDigit() {
        assertThat(TurkishPii.validVkn("1234567890")).isTrue();
        assertThat(TurkishPii.validVkn("4840847211")).isTrue();
        assertThat(TurkishPii.validVkn("1234567891")).isFalse();
        assertThat(TurkishPii.validVkn("4840847212")).isFalse();
        assertThat(TurkishPii.validVkn("484084721")).isFalse();
    }

    @Test
    void vkn_needsATaxWordBeforeIt() {
        assertThat(found("Vergi kimlik numarası: 4840847211", Type.VKN)).containsExactly("4840847211");
        assertThat(found("VERGİ NO 1234567890", Type.VKN)).containsExactly("1234567890");
        assertThat(found("Firmanın V.K.N. bilgisi 4840847211 olarak geçer", Type.VKN)).containsExactly("4840847211");
        assertThat(found("vkn=1234567890", Type.VKN)).containsExactly("1234567890");
    }

    @Test
    void vkn_withoutContextOrWithAWrongDigit_isNotOne() {
        assertThat(found("Sipariş 1234567890 hazırlanıyor.", Type.VKN)).as("valid digit, no tax word").isEmpty();
        assertThat(found("Vergi no 1234567891", Type.VKN)).as("wrong check digit").isEmpty();
        assertThat(found("Vergi dairesi Kadıköy. " + "x".repeat(50) + " 4840847211", Type.VKN))
                .as("tax word too far away").isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ IBAN

    @Test
    void iban_mod97_compactAndGrouped() {
        assertThat(TurkishPii.validIban("TR330006100519786457841326")).isTrue();
        assertThat(TurkishPii.validIban("TR340006100519786457841326")).isFalse();
        assertThat(TurkishPii.validIban("TR33000610051978645784132")).isFalse();
        assertThat(found("IBAN: TR33 0006 1005 1978 6457 8413 26 adına", Type.IBAN))
                .containsExactly("TR33 0006 1005 1978 6457 8413 26");
        assertThat(found("hesap TR330006100519786457841326'ya yatırıldı", Type.IBAN))
                .containsExactly("TR330006100519786457841326");
        assertThat(found("küçük harfle tr330006100519786457841326", Type.IBAN)).hasSize(1);
        assertThat(found("TR34 0006 1005 1978 6457 8413 26", Type.IBAN)).as("mod-97 fails").isEmpty();
    }

    @Test
    void iban_isOneFinding_notACardOrTcknInside() {
        assertThat(types("TR33 0006 1005 1978 6457 8413 26")).containsExactly(Type.IBAN);
    }

    // ------------------------------------------------------------------------------------------------ cards

    @Test
    void card_luhnAndNetworkPrefix() {
        assertThat(TurkishPii.luhn("4111111111111111")).isTrue();
        assertThat(TurkishPii.luhn("4111111111111112")).isFalse();
        assertThat(found("Kart: 4111 1111 1111 1111", Type.CARD)).containsExactly("4111 1111 1111 1111");
        assertThat(found("kart 5555-5555-5555-4444 ile", Type.CARD)).containsExactly("5555-5555-5555-4444");
        assertThat(found("Amex 3782 822463 10005", Type.CARD)).containsExactly("3782 822463 10005");
        assertThat(found("Troy 9792030000000000", Type.CARD)).containsExactly("9792030000000000");
        assertThat(found("4111111111111111", Type.CARD)).hasSize(1);
    }

    @Test
    void card_lookAlikes_stayReadable() {
        assertThat(found("Kart 4111 1111 1111 1112", Type.CARD)).as("Luhn fails").isEmpty();
        assertThat(found("Takip no 4829103756281934", Type.CARD)).as("Luhn fails").isEmpty();
        assertThat(found("Referans 1111111111111117", Type.CARD)).as("no card network starts with 1").isEmpty();
        assertThat(found("Mixed 4111 1111-1111 1111", Type.CARD)).as("inconsistent grouping").isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ e-mail

    @Test
    void email_withTurkishLettersAndSentencePunctuation() {
        assertThat(found("İletişim: ayse.yilmaz@ornek.com.tr.", Type.EMAIL)).containsExactly("ayse.yilmaz@ornek.com.tr");
        assertThat(found("(mehmet+ik@şirket.com.tr) adresine", Type.EMAIL)).containsExactly("mehmet+ik@şirket.com.tr");
        assertThat(found("Yanıt: destek@ornek.com'a yazın", Type.EMAIL)).containsExactly("destek@ornek.com");
    }

    @Test
    void email_lookAlikes_stayReadable() {
        assertThat(found("Toplantı @ofis 15:00", Type.EMAIL)).isEmpty();
        assertThat(found("kullanici@localhost", Type.EMAIL)).as("no top-level domain").isEmpty();
        assertThat(found("adres@ornek.c0m", Type.EMAIL)).as("digits in the top-level domain").isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ phones

    @ParameterizedTest
    @ValueSource(strings = {"+90 532 123 45 67", "+905321234567", "0090 532 123 45 67", "0532 123 45 67",
            "0532 123 4567", "0 532 123 45 67", "05321234567", "0532-123-45-67", "0532.123.45.67", "5321234567",
            "(0532) 123 45 67", "+90 (532) 123-45-67", "0212 555 12 34", "(212) 555 12 34", "+90 216 555 1234",
            "0850 222 33 44", "905051234567"})
    void phone_commonSpellings(String phone) {
        assertThat(found("Beni " + phone + " numarasından arayın.", Type.PHONE)).containsExactly(phone);
    }

    @Test
    void phone_lookAlikes_stayReadable() {
        assertThat(found("Sipariş 2125551234 yolda", Type.PHONE)).as("bare area code: an order number").isEmpty();
        assertThat(found("Kod 0599 123 45 67", Type.PHONE)).as("599 is no operator").isEmpty();
        assertThat(found("Tarih 15.01.2024 saat 14:30", Type.PHONE)).isEmpty();
        assertThat(found("Tutar 1.250.000,00 TL", Type.PHONE)).isEmpty();
        assertThat(found("No 53212345678", Type.PHONE)).as("eleven digits").isEmpty();
    }

    @Test
    void phone_sameNumberWrittenTwoWays_hasOneKey() {
        List<TurkishPii.Match> matches = TurkishPii.find("Ev: +90 532 123 45 67, iş: 0532-123-4567");
        assertThat(matches).extracting(TurkishPii.Match::key).containsExactly("5321234567", "5321234567");
    }

    // ------------------------------------------------------------------------------------------------ plates

    @ParameterizedTest
    @ValueSource(strings = {"34 ABC 123", "34 ABC 12", "06 A 1234", "06 A 12345", "35 AB 1234", "35 AB 123",
            "35AB1234", "81 XY 999", "01 D 4567"})
    void plate_validShapes(String plate) {
        assertThat(found("Araç " + plate + " plakalı, otoparkta.", Type.PLATE)).containsExactly(plate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"82 AB 123", "00 AB 123", "34 ABCD 12", "34 A 123", "34 AB 12", "34 ABC 1234",
            "34 abc 123", "16 GB 512", "10 TL 250", "12 KG 500"})
    void plate_lookAlikes_stayReadable(String text) {
        assertThat(found("Not: " + text + " kaydı", Type.PLATE)).isEmpty();
    }

    // ------------------------------------------------------------------------------------------------ together

    @Test
    void findings_comeInOrder_withoutOverlaps() {
        String text = "Ad: Ayşe; TC 10000000146; tel 0532 123 45 67; IBAN TR33 0006 1005 1978 6457 8413 26; "
                + "e-posta ayse.yilmaz@ornek.com.tr; araç 34 ABC 123; kart 4111 1111 1111 1111.";
        List<TurkishPii.Match> matches = TurkishPii.find(text);

        assertThat(matches).extracting(TurkishPii.Match::type).containsExactly(Type.AD, Type.TCKN, Type.PHONE,
                Type.IBAN, Type.EMAIL, Type.PLATE, Type.CARD);
        for (int i = 1; i < matches.size(); i++) {
            assertThat(matches.get(i).start()).isGreaterThanOrEqualTo(matches.get(i - 1).end());
        }
    }

    /** The ASCII digits of {@code ascii} written in the script whose zero is {@code zero}. */
    private static String digitsFrom(char zero, String ascii) {
        StringBuilder b = new StringBuilder();
        for (char c : ascii.toCharArray()) b.append((char) (zero + (c - '0')));
        return b.toString();
    }

    // ------------------------------------------------------------------------------------------------ spelling
    // ADR-0059 review: look-alike characters and separators must not let a value through.

    @Test
    void tckn_withInvisibleCharactersOtherDigitsOrGroups_isStillFound_andMapsBackToTheOriginalSpan() {
        String zeroWidth = "TC 100​000‌00146 kayıtlı";
        assertThat(found(zeroWidth, Type.TCKN)).containsExactly("100​000‌00146");
        assertThat(TurkishPii.find(zeroWidth)).singleElement().extracting(TurkishPii.Match::key).isEqualTo("10000000146");
        assertThat(found("TC " + digitsFrom('０', "10000000146"), Type.TCKN)).as("fullwidth digits").hasSize(1);
        assertThat(found("TC " + digitsFrom('٠', "10000000146"), Type.TCKN)).as("Arabic-Indic digits").hasSize(1);
        assertThat(found("TC " + digitsFrom('۰', "10000000146"), Type.TCKN)).as("Persian digits").hasSize(1);
        assertThat(found("IBAN TR" + digitsFrom('０', "330006100519786457841326"), Type.IBAN)).hasSize(1);
        assertThat(found("TC 100 000 001 46 numaralı", Type.TCKN)).containsExactly("100 000 001 46");
        assertThat(found("TC 100.000.001.46 numaralı", Type.TCKN)).containsExactly("100.000.001.46");
        assertThat(found("TC 100-000-001-46", Type.TCKN)).containsExactly("100-000-001-46");
        assertThat(found("Tutar 1.100.000.001.46", Type.TCKN)).as("part of a longer grouping").isEmpty();
        assertThat(found("Seri 100 000 001 47", Type.TCKN)).as("check digits still apply").isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"TR33 0006 1005 1978 6457 8413 26",
            "TR33-0006-1005-1978-6457-8413-26", "TR33  0006  1005  1978  6457  8413  26",
            "TR33 0006 1005\n1978 6457 8413 26", "TR33 0006 10\n05 1978 6457 8413 26", "TR33.0006.1005.1978.6457.8413.26",
            "TR33 0006 1005 1978 6457 8413 26"})
    void iban_withOtherSeparatorsOrWrapped_isStillFound(String iban) {
        assertThat(found("IBAN " + iban + " hesabına", Type.IBAN)).containsExactly(iban);
        assertThat(TurkishPii.find("IBAN " + iban)).singleElement().extracting(TurkishPii.Match::key)
                .isEqualTo("TR330006100519786457841326");
    }

    @Test
    void iban_gluedToAWord_isStillFound() {
        assertThat(found("hesapTR330006100519786457841326'ya", Type.IBAN)).containsExactly("TR330006100519786457841326");
        assertThat(found("No:5TR330006100519786457841326", Type.IBAN)).as("a digit before is another number").isEmpty();
    }

    @Test
    void phoneEmailAndCard_withNoBreakSpacesFullwidthSignsOrDots_areStillFound() {
        assertThat(found("Tel 0532 123 45 67", Type.PHONE)).containsExactly("0532 123 45 67");
        assertThat(found("Tel 0532 123 45 67", Type.PHONE)).as("narrow no-break space").hasSize(1);
        assertThat(found("e-posta ayse.yilmaz＠ornek.com.tr", Type.EMAIL)).containsExactly("ayse.yilmaz＠ornek.com.tr");
        assertThat(found("Kart 4111.1111.1111.1111", Type.CARD)).containsExactly("4111.1111.1111.1111");
        assertThat(found("Kart 4111­1111­1111­1111", Type.CARD)).as("soft hyphens").hasSize(1);
    }

    @Test
    void ordinaryBusinessText_hasNoFindings() {
        String text = "2024 yılı 3. çeyrek raporu: ciro 12.500.000 TL, 1.250 sipariş (ortalama 10.000 TL), "
                + "teslim süresi 3-5 iş günü. Madde 12/3 uyarınca 15.01.2024 tarihinde yürürlüğe girdi. "
                + "Sürüm 2.14.0, port 8080, sipariş no 4829103756281934, stok kodu ABC-1234-XY.";

        assertThat(TurkishPii.find(text)).isEmpty();
        assertThat(TurkishPii.find("")).isEmpty();
        assertThat(TurkishPii.find(null)).isEmpty();
    }
}
