package com.flooring.salesportal.common.error;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandlerTest.StubController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void notFoundException_returns404() throws Exception {
        mockMvc.perform(get("/test/not-found"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("nope"));
    }

    @Test
    void validationException_returns400() throws Exception {
        mockMvc.perform(get("/test/validation"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").value("bad"));
    }

    @Test
    void conflictException_returns409() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("CONFLICT"))
                .andExpect(jsonPath("$.error.message").value("dup"));
    }

    @Test
    void unauthorizedException_returns401() throws Exception {
        mockMvc.perform(get("/test/unauthorized"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.error.message").value("no session"));
    }

    @Test
    void forbiddenException_returns403() throws Exception {
        mockMvc.perform(get("/test/forbidden"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error.message").value("no access"));
    }

    @Test
    void businessRuleException_returns422() throws Exception {
        mockMvc.perform(get("/test/business-rule"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.error.message").value("locked"));
    }

    @Test
    void beanValidation_returns400WithFieldDetail() throws Exception {
        mockMvc.perform(post("/test/valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[*].field", hasItem("name")));
    }

    @Test
    void beanValidation_translatesCamelCaseFieldToSnakeCase() throws Exception {
        mockMvc.perform(post("/test/valid-camel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[*].field", hasItem("first_name")))
                .andExpect(jsonPath("$.error.details[*].field", org.hamcrest.Matchers.not(hasItem("firstName"))));
    }

    @Test
    void responseStatusException_preservesCarriedStatus() throws Exception {
        mockMvc.perform(get("/test/response-status-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("NOT_FOUND"));
    }

    @Test
    void responseStatusException_unlistedClientError_mapsToValidationFailed() throws Exception {
        mockMvc.perform(get("/test/response-status-405"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void httpRequestMethodNotSupported_implementsErrorResponseButNotErrorResponseException_preservesStatus() throws Exception {
        // /test/not-found is GET-only; POSTing triggers HttpRequestMethodNotSupportedException,
        // which implements org.springframework.web.ErrorResponse but does NOT extend ErrorResponseException.
        // Must still resolve to 405 + VALIDATION_FAILED, never 500 INTERNAL_SERVER_ERROR.
        mockMvc.perform(post("/test/not-found"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void malformedJson_returns400MalformedJson() throws Exception {
        mockMvc.perform(post("/test/valid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("MALFORMED_JSON"));
    }

    @Test
    void pathVarTypeMismatch_returns400() throws Exception {
        mockMvc.perform(get("/test/type-mismatch/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("id"));
    }

    @Test
    void missingRequiredQueryParam_returns400() throws Exception {
        mockMvc.perform(get("/test/missing-param"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.details[0].field").value("name"));
    }

    @Test
    void unhandledException_returns500NoStackTraceLeaked() throws Exception {
        mockMvc.perform(get("/test/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
                .andExpect(jsonPath("$.error.message").value("An unexpected error occurred."))
                .andExpect(jsonPath("$.error.details").doesNotExist());
    }

    @RestController
    @RequestMapping("/test")
    static class StubController {

        @GetMapping("/not-found")
        public void notFound() {
            throw new NotFoundException("nope");
        }

        @GetMapping("/validation")
        public void validation() {
            throw new ValidationException("bad");
        }

        @GetMapping("/conflict")
        public void conflict() {
            throw new ConflictException("dup");
        }

        @GetMapping("/unauthorized")
        public void unauthorized() {
            throw new UnauthorizedException("no session");
        }

        @GetMapping("/forbidden")
        public void forbidden() {
            throw new ForbiddenException("no access");
        }

        @GetMapping("/business-rule")
        public void businessRule() {
            throw new BusinessRuleException("locked");
        }

        @PostMapping("/valid")
        public void valid(@Valid @RequestBody Payload payload) {
        }

        @PostMapping("/valid-camel")
        public void validCamel(@Valid @RequestBody CamelPayload payload) {
        }

        @GetMapping("/type-mismatch/{id}")
        public void typeMismatch(@PathVariable int id) {
        }

        @GetMapping("/missing-param")
        public void missingParam(@RequestParam String name) {
        }

        @GetMapping("/response-status-404")
        public void responseStatus404() {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found.");
        }

        @GetMapping("/response-status-405")
        public void responseStatus405() {
            throw new ResponseStatusException(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed.");
        }

        @GetMapping("/boom")
        public void boom() {
            throw new RuntimeException("uh oh");
        }

        record Payload(@NotBlank String name) {
        }

        record CamelPayload(@NotBlank String firstName) {
        }
    }
}
