package com.verso.qa.service;

import com.verso.document.api.dto.PageResponse;
import com.verso.platform.security.web.AccountId;
import com.verso.qa.api.dto.SkillDraftRequest;
import com.verso.qa.api.dto.SkillResponse;
import com.verso.qa.api.dto.SkillState;
import com.verso.qa.api.dto.SkillSuggestion;
import com.verso.qa.api.dto.SkillSummary;
import com.verso.qa.api.dto.SkillUpdateRequest;
import com.verso.qa.api.dto.SkillVersionDiff;
import java.util.List;
import java.util.UUID;

/** Skills (procedures) of the learning layer (ADR-0053), verso-admin only; the admin comes from the validated token. */
public interface SkillService {

    /** A SKILL.md bundle: a zip with {@code <name>/SKILL.md}. */
    record Export(String fileName, byte[] zip) {

        @Override
        public String toString() {
            return "Export[bytes=" + zip.length + "]";
        }
    }

    SkillResponse draft(AccountId admin, SkillDraftRequest request);

    PageResponse<SkillSummary> list(AccountId admin, SkillState state, int page, int size);

    List<SkillSuggestion> suggestions(AccountId admin);

    SkillResponse get(AccountId admin, UUID skillId);

    SkillResponse update(AccountId admin, UUID skillId, SkillUpdateRequest request);

    SkillResponse approve(AccountId admin, UUID skillId);

    SkillResponse archive(AccountId admin, UUID skillId);

    SkillVersionDiff diff(AccountId admin, UUID skillId, int from, int to);

    Export export(AccountId admin, UUID skillId);
}
