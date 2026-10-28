package com.verso.qa.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.verso.platform.core.handler.GlobalServiceExceptionHandler;
import com.verso.qa.api.dto.ModelTask;
import com.verso.qa.service.gateway.ModelGatewayAdmin;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * ADR-0056: the admin gateway endpoints bind through real MVC: a bad id, task or body is 400 before the service is
 * touched, and the 400 never echoes the key that came in the body. (The role check and the full flow: CI's
 * ModelGatewayApiTest.)
 */
class ModelGatewayControllerBindingTest {

    private static final String KEY = "sk-SYNTHETIC-binding-key-4242";

    private final ModelGatewayAdmin admin = mock(ModelGatewayAdmin.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ModelGatewayController(admin))
            .setControllerAdvice(new GlobalServiceExceptionHandler(Clock.systemUTC())).build();

    @Test
    void route_whenTaskOrIdIsNotValid_is400_andTheServiceIsNotCalled() throws Exception {
        mvc.perform(put("/v1/admin/model-gateway/routes/NOT_A_TASK").contentType(MediaType.APPLICATION_JSON)
                .content("{\"entries\":[]}")).andExpect(status().isBadRequest());
        mvc.perform(get("/v1/admin/model-gateway/connections/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(put("/v1/admin/model-gateway/routes/ANSWER").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
        verifyNoInteractions(admin);
    }

    @Test
    void update_whenTheBodyIsInvalid_is400_withoutEchoingTheKey() throws Exception {
        MvcResult result = mvc.perform(put("/v1/admin/model-gateway/connections/" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"type\":\"OPENAI\",\"models\":[],\"apiKey\":\"" + KEY + "\"}"))
                .andExpect(status().isBadRequest()).andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain(KEY);
        verifyNoInteractions(admin);
    }

    @Test
    void route_whenValid_reachesTheServiceWithTheTask() throws Exception {
        UUID connection = UUID.randomUUID();
        mvc.perform(put("/v1/admin/model-gateway/routes/FOLLOW_UP").contentType(MediaType.APPLICATION_JSON)
                .content("{\"entries\":[{\"connectionId\":\"" + connection + "\",\"model\":\"m-1\"}]}"))
                .andExpect(status().isOk());

        verify(admin).setRoute(eq(ModelTask.FOLLOW_UP), any());
    }
}
