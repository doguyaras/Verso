package com.verso.audit.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.audit.api.dto.AuditEntryResponse;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditOutcome;
import com.verso.audit.api.enums.AuditTargetType;
import java.io.IOException;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** CSV injection and quoting of the audit export (ADR-0026; OWASP "CSV Injection"). */
class AuditCsvTest {

    @Test
    void cell_whenItStartsLikeAFormula_isPrefixedWithAnApostrophe() {
        assertThat(AuditCsv.cell("=1+1")).isEqualTo("'=1+1");
        assertThat(AuditCsv.cell("+1")).isEqualTo("'+1");
        assertThat(AuditCsv.cell("-1")).isEqualTo("'-1");
        assertThat(AuditCsv.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(AuditCsv.cell("\t=1")).isEqualTo("'\t=1");
        assertThat(AuditCsv.cell("\r=1")).isEqualTo("\"'\r=1\"");
    }

    @Test
    void cell_whenItIsPlainOrNull_staysAsItIs() {
        assertThat(AuditCsv.cell("acct-1")).isEqualTo("acct-1");
        assertThat(AuditCsv.cell("a=b")).as("only the first character counts").isEqualTo("a=b");
        assertThat(AuditCsv.cell(null)).isEmpty();
        assertThat(AuditCsv.cell("")).isEmpty();
    }

    @Test
    void cell_whenItHoldsSeparatorsQuotesOrLineBreaks_isQuotedPerRfc4180() {
        assertThat(AuditCsv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(AuditCsv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(AuditCsv.cell("a\nb")).isEqualTo("\"a\nb\"");
        assertThat(AuditCsv.cell("=a,b")).as("guard first, then quote").isEqualTo("\"'=a,b\"");
    }

    @Test
    void row_whenWritten_hasOneCellPerHeaderColumnAndGuardsTheSubject() throws IOException {
        UUID id = UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        AuditEntryResponse entry = new AuditEntryResponse(id, Instant.parse("2026-10-24T10:15:30.123456Z"), "-evil",
                List.of("verso-admin", "verso-user"), AuditAction.QUESTION_ASKED, AuditOutcome.SUCCESS,
                AuditTargetType.TURN, id, null, "ANSWERED", "gemma4:e2b", List.of(doc), "a".repeat(32), "local");
        StringWriter out = new StringWriter();

        AuditCsv.writeHeader(out);
        AuditCsv.writeRow(out, entry);

        String[] lines = out.toString().split("\r\n");
        assertThat(lines).hasSize(2);
        assertThat(lines[0].split(",")).hasSize(14);
        assertThat(lines[1]).startsWith(id + ",2026-10-24T10:15:30.123456Z,'-evil,verso-admin verso-user,QUESTION_ASKED,"
                + "SUCCESS,TURN," + id + ",,ANSWERED,gemma4:e2b," + doc + ",");
        assertThat(lines[1]).endsWith("," + "a".repeat(32) + ",local");
    }
}
