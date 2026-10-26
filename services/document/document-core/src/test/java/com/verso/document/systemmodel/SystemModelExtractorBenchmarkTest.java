package com.verso.document.systemmodel;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.systemmodel.SystemModelExtractor.ChunkText;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A rough cost measurement of the deterministic pass (ADR-0054, docs/evidence/sistem-modeli-dogrulama.md): a dense
 * synthetic page of about 3 000 characters (codes, terms, a numbered process, menu paths, owner phrases). Not a JMH
 * benchmark: it warms up, measures, prints the per-page time and fails only far above the budget (CI machines vary).
 */
class SystemModelExtractorBenchmarkTest {

    static final String PAGE = """
            Depo İade Kabul Süreci
            Bu süreçten Depo Sorumlusu sorumludur. Kayıtlar Depo Yönetim Sisteminde tutulur.
            1. WMS > Stok > İade Girişi menüsünden iade kaydı açılır.
            2. Ürün etiketi okutulur; FEFO (First Expired First Out) kuralına göre raf seçilir.
            3. WMS-204 hatası alınırsa stok sayımı istenir.
            4. Kalite Uzmanı tarafından hasar kontrolü yapılır.
            5. SKT (Son Kullanma Tarihi) geçmiş ürünler imha alanına alınır.
            Hata kodları
            WMS-204: Stok yetersiz, sayım gerekir.
            WMS-310: Raf kapasitesi dolu.
            ERP-12 | Fatura eşleşmedi | Muhasebe
            İK-3 | İzin talebi onay bekliyor | İnsan Kaynakları
            Sevkiyat Süreci
            1. Sipariş Giriş ekranında müşteri seçilir.
            2. Toplama listesi ERP üzerinden basılır.
            3. Sevkiyat Şefi tarafından araç onaylanır.
            Notlar: Depo Müdürü Ahmet Yılmaz tarafından aylık rapor hazırlanır. KDV (yüzde 20) fiyata eklenir.
            Lojistik tarafında TMS (Transportation Management System) kullanılır; P7 kodu sevkiyatın beklemede olduğunu
            gösterir. Faturalar ERP üzerinden kesilir ve SAT-E058 uyarısı görülürse Muhasebe Departmanı sorumludur.
            """.repeat(2);

    @Test
    void extract_whenDensePage_staysWellBelowTheIngestionBudget() {
        List<ChunkText> document = new ArrayList<>();
        for (int i = 0; i < 100; i++) document.add(new ChunkText(i, PAGE));
        for (int warm = 0; warm < 5; warm++) SystemModelExtractor.extract(document);

        int rounds = 10;
        long started = System.nanoTime();
        ExtractedModel model = null;
        for (int r = 0; r < rounds; r++) model = SystemModelExtractor.extract(document);
        double perPageMs = (System.nanoTime() - started) / 1e6 / rounds / document.size();

        System.out.printf("System model extraction: %.3f ms per page (%d chars), entities=%d%n", perPageMs,
                PAGE.length(), model.entities().size());
        assertThat(model.entities()).isNotEmpty();
        // The embedding of the same page takes about a second on a CPU laptop (docs/capacity.md); 20 ms is a 2 % ceiling.
        assertThat(perPageMs).isLessThan(20.0);
    }
}
