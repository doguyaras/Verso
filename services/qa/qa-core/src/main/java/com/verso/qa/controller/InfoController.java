package com.verso.qa.controller;

import com.verso.platform.security.authority.CallerRoles;
import com.verso.qa.api.dto.ServiceInfoResponse;
import com.verso.qa.config.VersoAiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/info (ADR-0015): the mode and model names and the caller's own Verso roles (ADR-0019) for any signed-in caller;
 * nothing about other accounts. Not cached: the roles belong to the caller.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/info")
public class InfoController {

    private final VersoAiProperties ai;
    private final CallerRoles callerRoles;

    public InfoController(VersoAiProperties ai, CallerRoles callerRoles) {
        this.ai = ai;
        this.callerRoles = callerRoles;
    }

    @GetMapping
    public ResponseEntity<ServiceInfoResponse> info() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(new ServiceInfoResponse(ai.mode().header(), ai.chatModel(), ai.embeddingModel(),
                        callerRoles.current()));
    }
}
