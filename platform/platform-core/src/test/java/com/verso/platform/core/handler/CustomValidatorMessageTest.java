package com.verso.platform.core.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A Spring Validator (not Bean Validation) has no message template to judge, and its default message may quote the
 * value: only the generic text is shown, for object and field errors alike (third-round test review J05).
 */
class CustomValidatorMessageTest {

    static final String MARKER = "custom-validator-marker-4711";

    record Body(String title) {}

    @RestController
    static class Probe {

        @InitBinder
        void binder(WebDataBinder binder) {
            binder.addValidators(new Validator() {
                @Override
                public boolean supports(Class<?> type) {
                    return Body.class.equals(type);
                }

                @Override
                public void validate(Object target, Errors errors) {
                    String title = ((Body) target).title();
                    errors.reject("title.taken", "title " + title + " already exists");
                    errors.rejectValue("title", "title.reserved", "title " + title + " is reserved");
                }
            });
        }

        @PostMapping("/v1/custom")
        ResponseEntity<Void> create(@Validated @RequestBody Body body) {
            return ResponseEntity.noContent().build();
        }
    }

    @Test
    void requestBody_whenSpringValidatorRejects_returnsGenericMessageWithoutValue() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new Probe())
                .setControllerAdvice(new GlobalServiceExceptionHandler(Clock.systemUTC())).build();

        MvcResult result = mvc.perform(post("/v1/custom").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + MARKER + "\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("\"title=invalid\"").contains("\"body=invalid\"").doesNotContain(MARKER);
    }
}
