package com.flooring.salesportal.common.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class ApiResponseTest {

    @Autowired
    private ObjectMapper mapper;

    @Test
    void okWithDataOnly_omitsMessageAndPagination() throws Exception {
        String json = mapper.writeValueAsString(ApiResponse.ok(42));

        assertThat(json).isEqualTo("{\"data\":42}");
    }

    @Test
    void okWithMessage_includesMessage_omitsPagination() throws Exception {
        String json = mapper.writeValueAsString(ApiResponse.ok(42, "Done."));

        assertThat(json).isEqualTo("{\"data\":42,\"message\":\"Done.\"}");
    }

    @Test
    void okWithNullDataAndMessage_keepsDataKey() throws Exception {
        String json = mapper.writeValueAsString(ApiResponse.ok(null, "Logged out."));

        assertThat(json).isEqualTo("{\"data\":null,\"message\":\"Logged out.\"}");
    }

    @Test
    void pageSerializesPaginationInSnakeCase() throws Exception {
        ApiResponse<List<Integer>> response = ApiResponse.page(
                List.of(1, 2, 3),
                new PaginationMeta(1, 20, 84L, 5)
        );

        String json = mapper.writeValueAsString(response);

        assertThat(json).isEqualTo(
                "{\"data\":[1,2,3],"
                        + "\"pagination\":{\"page\":1,\"page_size\":20,\"total_items\":84,\"total_pages\":5}}"
        );
    }
}
