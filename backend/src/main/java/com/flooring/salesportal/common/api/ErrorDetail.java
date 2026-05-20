package com.flooring.salesportal.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ErrorDetail(
        @JsonInclude(JsonInclude.Include.NON_NULL) String section,
        @JsonInclude(JsonInclude.Include.NON_NULL) String field,
        String message
) {
}
