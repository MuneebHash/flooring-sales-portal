package com.flooring.salesportal.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class ErrorResponseTest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    void validationErrorMatchesConventionsExample() throws Exception {
        ErrorResponse response = new ErrorResponse(new ErrorBody(
                "VALIDATION_FAILED",
                "One or more fields are invalid.",
                List.of(new ErrorDetail(null, "quantity_lm", "Must be greater than 0"))
        ));

        String json = mapper.writeValueAsString(response);

        assertThat(json).isEqualTo(
                "{\"error\":{"
                        + "\"code\":\"VALIDATION_FAILED\","
                        + "\"message\":\"One or more fields are invalid.\","
                        + "\"details\":[{\"field\":\"quantity_lm\",\"message\":\"Must be greater than 0\"}]"
                        + "}}"
        );
    }

    @Test
    void detailsKeyOmittedWhenNull() throws Exception {
        ErrorResponse response = new ErrorResponse(new ErrorBody(
                "INVALID_CREDENTIALS",
                "Invalid salesperson code or password.",
                null
        ));

        String json = mapper.writeValueAsString(response);

        assertThat(json).isEqualTo(
                "{\"error\":{"
                        + "\"code\":\"INVALID_CREDENTIALS\","
                        + "\"message\":\"Invalid salesperson code or password.\""
                        + "}}"
        );
    }

    @Test
    void detailsKeyOmittedWhenEmpty() throws Exception {
        ErrorResponse response = new ErrorResponse(new ErrorBody(
                "VALIDATION_FAILED",
                "One or more fields are invalid.",
                List.of()
        ));

        String json = mapper.writeValueAsString(response);

        assertThat(json).isEqualTo(
                "{\"error\":{"
                        + "\"code\":\"VALIDATION_FAILED\","
                        + "\"message\":\"One or more fields are invalid.\""
                        + "}}"
        );
    }

    @Test
    void detailWithSectionAndField_keepsBoth() throws Exception {
        ErrorResponse response = new ErrorResponse(new ErrorBody(
                "INVOICE_PRECONDITIONS_NOT_MET",
                "Complete required fields before creating invoice.",
                List.of(new ErrorDetail("customer", "last_name", "Customer last name is required."))
        ));

        String json = mapper.writeValueAsString(response);

        assertThat(json).contains("\"section\":\"customer\"");
        assertThat(json).contains("\"field\":\"last_name\"");
        assertThat(json).contains("\"message\":\"Customer last name is required.\"");
    }

    @Test
    void detailWithNullSectionAndField_omitsThem() throws Exception {
        ErrorResponse response = new ErrorResponse(new ErrorBody(
                "VALIDATION_FAILED",
                "Invalid.",
                List.of(new ErrorDetail(null, null, "Something is wrong."))
        ));

        String json = mapper.writeValueAsString(response);

        assertThat(json).doesNotContain("\"section\"");
        assertThat(json).doesNotContain("\"field\"");
        assertThat(json).contains("\"message\":\"Something is wrong.\"");
    }
}
