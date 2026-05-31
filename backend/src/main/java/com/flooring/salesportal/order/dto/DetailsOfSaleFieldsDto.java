package com.flooring.salesportal.order.dto;

import java.time.LocalDate;

/**
 * The five validated, normalised "details of sale" fields, nested under
 * {@code data.details_of_sale_fields} in the {@code PUT /orders/{orderId}/details-of-sale} 200
 * response. Same field set and nullability as the {@code sales_order} columns these map to — no
 * financial fields, no customer/address, no internal IDs. {@code proposedLayDate} serialises as
 * ISO {@code YYYY-MM-DD} (conventions §5), matching the GET workspace read.
 */
public record DetailsOfSaleFieldsDto(
        boolean supplyOnly,
        String planNumbers,
        LocalDate proposedLayDate,
        String layDateStatus,
        String detailsOfSale
) {
}
