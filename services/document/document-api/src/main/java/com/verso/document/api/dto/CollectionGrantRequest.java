package com.verso.document.api.dto;

import com.verso.document.api.enums.PrincipalType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Grant a collection to a group or a user (ADR-0028, verso-admin only). The value is compared byte for byte with the
 * token: a Keycloak group by its full path ({@code /hukuk/sozlesmeler}), an Entra group by its object id, a user by the
 * token's sub. Not trimmed, not case-folded: "/Hukuk" is another group. Bounded and printable like the claim values
 * the platform accepts (ADR-0019), otherwise no token could ever match it.
 */
public record CollectionGrantRequest(
        @NotNull PrincipalType principalType,
        @NotBlank @Size(max = 256)
        @Pattern(regexp = "[^\\p{Cc}\\p{Cf}]*", message = "must not contain control or format characters")
        String principal) {
}
