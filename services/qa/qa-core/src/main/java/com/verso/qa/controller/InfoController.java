package com.verso.qa.controller;

import com.verso.qa.api.dto.ServiceInfoResponse;
import com.verso.qa.config.VersoAiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /v1/info (ADR-0015): the mode and model names for any signed-in caller; nothing about other accounts. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/info")
public class InfoController {

    private final VersoAiProperties ai;

    public InfoController(VersoAiProperties ai) {
        this.ai = ai;
    }

    @GetMapping
    public ResponseEntity<ServiceInfoResponse> info() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(new ServiceInfoResponse(ai.mode().header(), ai.chatModel(), ai.embeddingModel()));
    }
}
