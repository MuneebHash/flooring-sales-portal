package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.order.dto.AddNoteResponse;
import com.flooring.salesportal.order.dto.NoteReadDto;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Chunk 3 Branch 4 order notes endpoints (D.12 GET list, D.13 POST append). Shares the
 * {@code /orders/{orderId}} base path with the other order sub-resource controllers; handler
 * mappings stay distinct by method + the {@code /notes} sub-path.
 *
 * <p>{@code orderId} is captured as a {@code String} (matching the rest of the order endpoints) so
 * non-numeric / non-positive values become VALIDATION_FAILED on {@code order_id} in the service
 * rather than a path-binding error. {@code page} / {@code page_size} are captured as {@code Integer}
 * (matching {@code OrderCatalogController}) so a non-numeric value becomes VALIDATION_FAILED on that
 * parameter via the global type-mismatch handler. The POST body is taken as a raw {@code String}
 * ({@code required = false}) so JSON parsing happens inside the service, strictly after the guard /
 * order-scope gates. The service builds the {@code ApiResponse} envelope for both endpoints.
 */
@RestController
@RequestMapping("/api/v1/{slug}/orders/{orderId}")
public class OrderNoteController {

    private final OrderNoteService orderNoteService;

    public OrderNoteController(OrderNoteService orderNoteService) {
        this.orderNoteService = orderNoteService;
    }

    @GetMapping("/notes")
    public ApiResponse<List<NoteReadDto>> listNotes(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            HttpServletRequest httpRequest) {

        return orderNoteService.getNotes(slug, orderId, page, pageSize, httpRequest);
    }

    @PostMapping("/notes")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AddNoteResponse> addNote(
            @PathVariable String slug,
            @PathVariable("orderId") String orderId,
            @RequestBody(required = false) String body,
            HttpServletRequest httpRequest) {

        return orderNoteService.addNote(slug, orderId, body, httpRequest);
    }
}
