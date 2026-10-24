package com.verso.privacy.service;

import com.verso.privacy.api.dto.AnswerRedactionRequest;
import com.verso.privacy.api.dto.AnswerRedactionResponse;
import com.verso.privacy.api.dto.SubjectDeletionRequest;
import com.verso.privacy.api.dto.SubjectDeletionResponse;
import com.verso.privacy.api.dto.SubjectSummaryResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/** KVKK data-subject requests and answer redaction (ADR-0034); verso-admin only. */
public interface SubjectRequestService {

    /** Most conversations in one export (each up to 200 turns, ADR-0023); the response says when it cut. */
    int MAX_EXPORT_CONVERSATIONS = 2_000;

    /** Most audit rows in one export, the same cap as the audit CSV export (ADR-0026). */
    int MAX_EXPORT_AUDIT_EVENTS = 100_000;

    SubjectSummaryResponse summary(String sub);

    /** Checks the sub, counts what the export will hold, and records the export in the audit log before it starts. */
    ExportPlan planExport(String sub);

    /** Streams the export as one JSON document, in bounded batches from each module. */
    void writeExport(ExportPlan plan, OutputStream out) throws IOException;

    SubjectDeletionResponse delete(String sub, SubjectDeletionRequest request);

    AnswerRedactionResponse redactAnswers(UUID documentId, AnswerRedactionRequest request);

    /**
     * @param summary   what exists when the export starts
     * @param truncated whether a cap (conversations, audit rows, document lists) cuts the export
     */
    record ExportPlan(String sub, SubjectSummaryResponse summary, boolean truncated) {

        @Override
        public String toString() {
            return "ExportPlan[truncated=" + truncated + "]";
        }
    }
}
