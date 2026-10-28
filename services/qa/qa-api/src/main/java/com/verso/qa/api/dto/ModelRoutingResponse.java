package com.verso.qa.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Every task's route and the gateway settings (ADR-0056).
 *
 * @param routes          task to route (primary first); a task without entries uses the built-in model
 * @param builtInModel    the built-in model (config/verso.yml): it answers a task whose route is empty or has no usable
 *                        model left (all disabled); an admin may also put it into a route as a step
 * @param userModelChoice whether users may pick among the answer route's models
 * @param keyStoreReady   whether the key-encryption key is present (/run/secrets); without it no key can be stored
 */
public record ModelRoutingResponse(Map<ModelTask, List<ModelRouteEntry>> routes, String builtInModel,
                                   boolean userModelChoice, boolean keyStoreReady) {
    public ModelRoutingResponse {
        routes = Map.copyOf(routes);
    }
}
