package com.flooring.salesportal.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

public record ErrorBody(
        String code,
        String message,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ErrorDetail> details
) {
}
