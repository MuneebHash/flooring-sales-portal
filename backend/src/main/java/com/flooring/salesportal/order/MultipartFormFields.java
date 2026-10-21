package com.flooring.salesportal.order;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.multipart.MultipartHttpServletRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The multipart FORM-FIELD versus URL query-string distinction of the invoice signing endpoints, shared
 * by the in-store D.8 accept and (Phase 16G PR 4) the public invoice accept. Moved verbatim from
 * {@code OrderInvoiceService} (16G PR 3); the callers keep their own check order and messages.
 *
 * <p>The servlet container reports URL query-string parameters through the same
 * {@code getParameterValues} / {@code getParameterNames} as the multipart form fields, so a carrier such
 * as {@code expected_version_number} must take the query-string occurrences out again (16G contract
 * §5.3 wants a form field; the conventions take no query-string data on a mutation).
 */
final class MultipartFormFields {

    private MultipartFormFields() {
    }

    /**
     * The values a multipart request carries for {@code name} as FORM FIELDS: the merged parameter values
     * minus each query-string occurrence of the name.
     */
    static List<String> formFieldValues(MultipartHttpServletRequest request, String name) {
        String[] merged = request.getParameterValues(name);
        if (merged == null) {
            return List.of();
        }
        List<String> values = new ArrayList<>(Arrays.asList(merged));
        for (String queryValue : queryStringParameters(request).getOrDefault(name, List.of())) {
            values.remove(queryValue);
        }
        return values;
    }

    /**
     * The request's URL query-string parameters, decoded as the container decodes parameters (UTF-8,
     * {@code +} as a space); a component that cannot be decoded is kept as it was sent.
     */
    static Map<String, List<String>> queryStringParameters(HttpServletRequest request) {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        String query = request.getQueryString();
        if (query == null || query.isEmpty()) {
            return parameters;
        }
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String rawName = equals < 0 ? pair : pair.substring(0, equals);
            String rawValue = equals < 0 ? "" : pair.substring(equals + 1);
            parameters.computeIfAbsent(decodeQueryComponent(rawName), key -> new ArrayList<>())
                    .add(decodeQueryComponent(rawValue));
        }
        return parameters;
    }

    private static String decodeQueryComponent(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException undecodable) {
            return raw;
        }
    }
}
