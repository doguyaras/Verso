package com.verso.qa.controller;

import com.verso.qa.api.dto.ModelChoicesResponse;
import com.verso.qa.service.gateway.ModelGateway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /v1/models (ADR-0056): the answer models any signed-in user may pick, only when a verso-admin allowed the
 * choice; otherwise {@code enabled=false}. Names and the external flag only: no address, no key, no price.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/models")
public class ModelChoiceController {

    private final ModelGateway gateway;

    public ModelChoiceController(ModelGateway gateway) {
        this.gateway = gateway;
    }

    @GetMapping
    public ResponseEntity<ModelChoicesResponse> choices() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(gateway.choices());
    }
}
