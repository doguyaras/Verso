package com.verso.document.systemmodel;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.api.enums.SystemEntityType;
import com.verso.document.api.enums.SystemRelationType;
import com.verso.document.systemmodel.ExtractedModel.Entity;
import com.verso.document.systemmodel.ExtractedModel.Ref;
import com.verso.document.systemmodel.ExtractedModel.Relation;
import com.verso.document.systemmodel.SystemModelExtractor.ChunkText;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SystemModelExtractorTest {

    private static ExtractedModel extract(String... chunks) {
        List<ChunkText> texts = new java.util.ArrayList<>();
        for (int i = 0; i < chunks.length; i++) texts.add(new ChunkText(i, chunks[i]));
        return SystemModelExtractor.extract(texts);
    }

    private static Optional<Entity> entity(ExtractedModel model, SystemEntityType type, String key) {
        return model.entities().stream().filter(e -> e.ref().equals(new Ref(type, key))).findFirst();
    }

    private static List<Relation> relations(ExtractedModel model, SystemRelationType type) {
        return model.relations().stream().filter(r -> r.type() == type).toList();
    }

    @Test
    void codes_whenDefinedInListOrTable_areDefinedAndFoldedWithTurkishI() {
        ExtractedModel model = extract("""
                Hata kodları
                WMS-204: Stok yetersiz, sayım gerekir.
                İK-3 | İzin talebi onay bekliyor | İnsan Kaynakları
                Raporda SAT-E058 görülür.""");

        assertThat(entity(model, SystemEntityType.CODE, "WMS-204")).isPresent();
        assertThat(entity(model, SystemEntityType.CODE, "IK-3")).get().extracting(Entity::label).isEqualTo("İK-3");
        assertThat(entity(model, SystemEntityType.CODE, "SAT-E058")).isPresent();
        assertThat(relations(model, SystemRelationType.DEFINES)).extracting(Relation::value)
                .contains("Stok yetersiz, sayım gerekir", "İzin talebi onay bekliyor");
        // SAT-E058 is only mentioned, never defined.
        assertThat(relations(model, SystemRelationType.DEFINES))
                .noneMatch(r -> r.source().key().equals("SAT-E058"));
        // WMS is a known system: the prefix links the code to it.
        assertThat(relations(model, SystemRelationType.CODE_OF))
                .anyMatch(r -> r.source().key().equals("WMS-204") && r.target().key().equals("WMS"));
    }

    @Test
    void codes_whenSaidToMeanSomething_areDefined() {
        ExtractedModel model = extract("P7 kodu sevkiyatın beklemede olduğunu gösterir.");

        assertThat(relations(model, SystemRelationType.DEFINES)).singleElement()
                .extracting(Relation::value).isEqualTo("sevkiyatın beklemede olduğunu");
    }

    @Test
    void terms_whenAbbreviationIsExpanded_defineTheExpansionInEveryForm() {
        ExtractedModel model = extract("""
                Depoda FEFO (First Expired First Out) kuralı geçerlidir.
                Son Kullanma Tarihi (SKT) etikette yazar.
                KDV (yüzde 20) fiyata eklenir.
                ABC: Aktif Bekleyen Cari""");

        assertThat(relations(model, SystemRelationType.DEFINES)).extracting(r -> r.source().key(), Relation::value)
                .contains(org.assertj.core.groups.Tuple.tuple("FEFO", "First Expired First Out"),
                        org.assertj.core.groups.Tuple.tuple("SKT", "Son Kullanma Tarihi"),
                        org.assertj.core.groups.Tuple.tuple("ABC", "Aktif Bekleyen Cari"));
        // "yüzde 20" does not spell KDV: no definition.
        assertThat(entity(model, SystemEntityType.TERM, "KDV")).isEmpty();
    }

    @Test
    void terms_whenUsedLater_areMentionedInThoseChunksAndFoldedForLookup() {
        ExtractedModel model = extract("FEFO (First Expired First Out) uygulanır.", "Sevkiyatta FEFO'ya uyulur.");

        assertThat(model.mentions()).filteredOn(m -> m.ref().key().equals("FEFO"))
                .extracting(m -> m.chunkIndex() + ":" + m.defining()).containsExactly("0:true", "1:false");
        assertThat(QuestionKeys.of("fefo nedir?")).contains("FEFO");
        assertThat(QuestionKeys.of("İk-3 ne demek")).contains("IK-3");
    }

    @Test
    void steps_whenNumberedUnderHeading_formAProcessWithOrderAndOwner() {
        ExtractedModel model = extract("""
                İade Kabul Süreci
                Bu süreçten Depo Sorumlusu sorumludur.
                1. Ürünü WMS ekranında arayın.
                2. Hasar kontrolünü yapın.
                3. Kalite Uzmanı tarafından onaylanır.""");

        Ref process = new Ref(SystemEntityType.PROCESS, "iade kabul sureci");
        assertThat(entity(model, SystemEntityType.PROCESS, process.key())).isPresent();
        assertThat(relations(model, SystemRelationType.STEP_OF)).hasSize(3).allMatch(r -> r.target().equals(process));
        assertThat(relations(model, SystemRelationType.NEXT_STEP)).extracting(r -> r.source().key() + ">" + r.target().key())
                .containsExactly("iade kabul sureci#1>iade kabul sureci#2", "iade kabul sureci#2>iade kabul sureci#3");
        assertThat(relations(model, SystemRelationType.OWNED_BY))
                .anyMatch(r -> r.source().equals(process) && r.target().key().equals("depo sorumlusu"))
                .anyMatch(r -> r.source().key().equals("iade kabul sureci#3") && r.target().key().equals("kalite uzmani"));
        assertThat(entity(model, SystemEntityType.STEP, "iade kabul sureci#2")).get()
                .extracting(Entity::ordinal).isEqualTo(2);
    }

    @Test
    void steps_whenListSpansChunks_continueTheSameProcess() {
        ExtractedModel model = extract("Sayım Süreci\n1. Rafı kilitleyin.\n2. Sayın.", "3. Farkı girin.");

        assertThat(relations(model, SystemRelationType.STEP_OF)).hasSize(3);
        assertThat(entity(model, SystemEntityType.STEP, "sayim sureci#3")).isPresent();
    }

    @Test
    void steps_whenOnlyOneNumberedLine_formNoProcess() {
        ExtractedModel model = extract("Not\n1. Tek madde.\nBaşka paragraf.");

        assertThat(model.entities()).noneMatch(e -> e.ref().type() == SystemEntityType.PROCESS);
    }

    @Test
    void screens_whenMenuPathOrNamedScreen_areScreensOfTheirSystem() {
        ExtractedModel model = extract("""
                WMS > Stok > Sayım Girişi menüsünden sayım açılır.
                Önce Sipariş Giriş ekranında müşteri seçilir.""");

        assertThat(entity(model, SystemEntityType.SCREEN, "wms > stok > sayim girisi")).isPresent();
        assertThat(relations(model, SystemRelationType.PART_OF))
                .anyMatch(r -> r.source().key().equals("wms > stok > sayim girisi") && r.target().key().equals("WMS"));
        assertThat(entity(model, SystemEntityType.SCREEN, "siparis giris ekrani")).isPresent();
    }

    @Test
    void systems_whenNamedAsCompound_areSystemsButSentenceStartsAreNot() {
        ExtractedModel model = extract("""
                Kayıtlar Depo Yönetim Sisteminde tutulur.
                Kullanıcı sistemine giriş yapar.
                Faturalar ERP üzerinden kesilir.""");

        assertThat(entity(model, SystemEntityType.SYSTEM, "depo yonetim sistem")).get()
                .extracting(Entity::label).isEqualTo("Depo Yönetim Sistemi");
        assertThat(entity(model, SystemEntityType.SYSTEM, "ERP")).isPresent();
        assertThat(model.entities()).noneMatch(e -> e.ref().key().startsWith("kullanici"));
    }

    @Test
    void owners_whenPhraseNamesAPerson_keepTheTitleOnlyOrNothing() {
        ExtractedModel model = extract("""
                Onay Süreci
                Depo Müdürü Ahmet Yılmaz tarafından imzalanır.
                Ayşe Kaya Satın Alma Müdürü olarak sorumludur.
                Mehmet Bey sorumludur.
                Sorumlu: Zeynep Hanım
                1. Talep açılır.
                2. Talep onaylanır.""");

        assertThat(model.entities()).filteredOn(e -> e.ref().type() == SystemEntityType.ROLE)
                .extracting(Entity::label).containsExactlyInAnyOrder("Depo Müdürü", "Satın Alma Müdürü");
        assertThat(model.entities()).extracting(Entity::label)
                .noneMatch(l -> l.contains("Ahmet") || l.contains("Yılmaz") || l.contains("Ayşe") || l.contains("Kaya")
                        || l.contains("Mehmet") || l.contains("Zeynep"));
    }

    @Test
    void extract_whenSameChunksTwice_isDeterministic() {
        String text = "İade Süreci\n1. WMS-204 kontrol edilir.\n2. FEFO (First Expired First Out) uygulanır.";

        assertThat(extract(text)).isEqualTo(extract(text));
        assertThat(extract(text).toString()).doesNotContain("WMS-204").doesNotContain("FEFO");
    }
}
