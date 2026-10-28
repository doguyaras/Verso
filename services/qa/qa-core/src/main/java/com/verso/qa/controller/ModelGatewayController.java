package com.verso.qa.controller;

import com.verso.platform.security.authority.VersoRoles;
import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import com.verso.qa.api.dto.ModelBudget;
import com.verso.qa.api.dto.ModelConnectionRequest;
import com.verso.qa.api.dto.ModelConnectionResponse;
import com.verso.qa.api.dto.ModelConnectionTestResponse;
import com.verso.qa.api.dto.ModelGatewaySettingsRequest;
import com.verso.qa.api.dto.ModelKeyRequest;
import com.verso.qa.api.dto.ModelPrice;
import com.verso.qa.api.dto.ModelRouteRequest;
import com.verso.qa.api.dto.ModelRoutingResponse;
import com.verso.qa.api.dto.ModelTask;
import com.verso.qa.api.dto.ModelUsageResponse;
import com.verso.qa.service.gateway.ModelGatewayAdmin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/admin/model-gateway (ADR-0056): model provider connections, routes, prices, budgets and usage, for verso-admin
 * only (ADR-0019; everyone else 403), declared once on the class. No response ever carries a key: requests take it
 * write-only, responses say only whether one is stored and its last four characters. Not cached.
 */
@RestController
@Validated
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/admin/model-gateway")
@PreAuthorize(VersoRoles.HAS_ADMIN)
public class ModelGatewayController {

    private static final CacheControl NO_STORE = CacheControl.noStore().cachePrivate();

    private final ModelGatewayAdmin admin;

    public ModelGatewayController(ModelGatewayAdmin admin) {
        this.admin = admin;
    }

    @GetMapping("/connections")
    public ResponseEntity<List<ModelConnectionResponse>> connections() {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(admin.list());
    }

    @GetMapping("/connections/{id}")
    public ResponseEntity<ModelConnectionResponse> connection(@PathVariable("id") UUID id) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(admin.get(id));
    }

    /** Validated with a tiny completion before it is stored (when enabled); 422 when the provider refuses. */
    @PostMapping("/connections")
    public ResponseEntity<ModelConnectionResponse> create(@CurrentAccount AccountId account,
                                                          @Valid @RequestBody ModelConnectionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(NO_STORE).body(admin.create(request,
                account.value()));
    }

    @PutMapping("/connections/{id}")
    public ResponseEntity<ModelConnectionResponse> update(@PathVariable("id") UUID id,
                                                          @Valid @RequestBody ModelConnectionRequest request) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(admin.update(id, request));
    }

    @PostMapping("/connections/{id}/key")
    public ResponseEntity<ModelConnectionResponse> rotateKey(@PathVariable("id") UUID id,
                                                             @Valid @RequestBody ModelKeyRequest request) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(admin.rotateKey(id, request.apiKey()));
    }

    @PostMapping("/connections/{id}/test")
    public ResponseEntity<ModelConnectionTestResponse> test(@PathVariable("id") UUID id) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(admin.test(id));
    }

    @DeleteMapping("/connections/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        admin.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/routes")
    public ResponseEntity<ModelRoutingResponse> routes() {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(admin.routing());
    }

    @PutMapping("/routes/{task}")
    public ResponseEntity<ModelRoutingResponse> route(@PathVariable("task") ModelTask task,
                                                      @Valid @RequestBody ModelRouteRequest request) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(admin.setRoute(task, request.entries()));
    }

    @PutMapping("/settings")
    public ResponseEntity<ModelRoutingResponse> settings(@Valid @RequestBody ModelGatewaySettingsRequest request) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(admin.setUserModelChoice(request.userModelChoice()));
    }

    @GetMapping("/prices")
    public ResponseEntity<List<ModelPrice>> prices() {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(admin.prices());
    }

    @PutMapping("/prices")
    public ResponseEntity<ModelPrice> price(@Valid @RequestBody ModelPrice price) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(admin.setPrice(price));
    }

    @DeleteMapping("/prices/{connectionId}")
    public ResponseEntity<Void> deletePrice(@PathVariable("connectionId") UUID connectionId,
                                            @RequestParam("model") @Size(max = 200) @Pattern(regexp = "[A-Za-z0-9._:/@-]+")
                                            String model) {
        admin.deletePrice(connectionId, model);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/budgets")
    public ResponseEntity<List<ModelBudget>> budgets() {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(admin.budgets());
    }

    @PutMapping("/budgets")
    public ResponseEntity<ModelBudget> budget(@Valid @RequestBody ModelBudget budget) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(admin.setBudget(budget));
    }

    @DeleteMapping("/budgets/{scope}")
    public ResponseEntity<Void> deleteBudget(@PathVariable("scope") @Pattern(regexp = "CONNECTION|TEAM") String scope,
                                             @RequestParam("key") @Size(min = 1, max = 200) String key) {
        admin.deleteBudget(scope, key);
        return ResponseEntity.noContent().build();
    }

    /** One month (YYYY-MM, default this month): never per user; teams only with k distinct users. */
    @GetMapping("/usage")
    public ResponseEntity<ModelUsageResponse> usage(
            @RequestParam(name = "month", required = false) @Pattern(regexp = "\\d{4}-\\d{2}") String month) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(admin.usage(month));
    }
}
