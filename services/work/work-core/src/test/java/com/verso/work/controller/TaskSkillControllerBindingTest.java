package com.verso.work.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.verso.document.api.dto.Reader;
import com.verso.platform.core.handler.GlobalServiceExceptionHandler;
import com.verso.platform.security.authority.CallerGroups;
import com.verso.platform.security.web.CurrentAccountArgumentResolver;
import com.verso.work.config.WorkProperties;
import com.verso.work.service.SkillCatalog;
import com.verso.work.service.SkillRunService;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * HTTP binding of the skill-run endpoints through real MVC (ADR-0057): the account comes only from the validated token,
 * a malformed id, body or rating never reaches the service, and exports are attachments.
 */
class TaskSkillControllerBindingTest {

    private final SkillCatalog catalog = mock(SkillCatalog.class);
    private final SkillRunService runs = mock(SkillRunService.class);
    private final CallerGroups groups = mock(CallerGroups.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        when(groups.current()).thenReturn(List.of("/ik"));
        WorkProperties properties = new WorkProperties(1, Duration.ofSeconds(1), Duration.ofSeconds(5), 3,
                Duration.ofSeconds(30), 90, 500, 40, Duration.ofMillis(100), Duration.ofMinutes(1));
        mvc = MockMvcBuilders.standaloneSetup(new TaskSkillController(catalog, runs, groups, properties))
                .setCustomArgumentResolvers(new CurrentAccountArgumentResolver())
                .setControllerAdvice(new GlobalServiceExceptionHandler(Clock.systemUTC()))
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void run_whenAuthenticated_readsTheAccountFromTheTokenNeverFromTheRequest() throws Exception {
        signIn("acct-token");
        UUID run = UUID.randomUUID();
        mvc.perform(get("/v1/skill-runs/" + run).param("accountId", "acct-other"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store, private"));
        ArgumentCaptor<Reader> reader = ArgumentCaptor.forClass(Reader.class);
        verify(runs).get(reader.capture(), eq(run));
        assertThat(reader.getValue().accountId()).isEqualTo("acct-token");
        assertThat(reader.getValue().groups()).containsExactly("/ik");
    }

    @Test
    void run_whenTheIdIsNotAUuid_is400AndNeverReachesTheService() throws Exception {
        signIn("acct-token");
        mvc.perform(get("/v1/skill-runs/not-a-uuid")).andExpect(status().isBadRequest());
        verifyNoInteractions(runs);
    }

    @Test
    void run_withoutAnAccount_is401AndNeverReachesTheService() throws Exception {
        mvc.perform(get("/v1/skill-runs/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        verifyNoInteractions(runs);
    }

    @Test
    void rating_whenOutOfRangeOrMissing_is400AndNeverReachesTheService() throws Exception {
        signIn("acct-token");
        String path = "/v1/skill-runs/" + UUID.randomUUID() + "/rating";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":9}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value(90000));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(runs);
    }

    @Test
    void start_withoutInputs_is400AndNeverReachesTheService() throws Exception {
        signIn("acct-token");
        mvc.perform(post("/v1/skills/premortem/runs").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(runs);
    }

    @Test
    void export_whenFound_isAnAttachmentThatIsNeverSniffed() throws Exception {
        signIn("acct-token");
        UUID run = UUID.randomUUID();
        when(runs.export(any(), eq(run), eq("JSON")))
                .thenReturn(new SkillRunService.Export("{}", "application/json", "premortem-1234abcd.json"));
        mvc.perform(get("/v1/skill-runs/" + run + "/export").param("format", "JSON"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"premortem-1234abcd.json\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    private static void signIn(String sub) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(sub).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }
}
