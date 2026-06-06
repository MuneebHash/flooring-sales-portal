package com.flooring.salesportal.order;

import com.flooring.salesportal.common.api.ErrorDetail;
import com.flooring.salesportal.order.dto.OrderFinancialSummaryDto;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * The 9 invoice-creation preconditions (Phase 12 Chunk 4 F.1 / conventions §14). Checked on D.1
 * Create (and the later D.2 manual Rewrite). Returns one {@link ErrorDetail} per FAILING precondition
 * (section + field + message) so the caller can surface EVERY missing item in a single 422
 * {@code INVOICE_PRECONDITIONS_NOT_MET} — not just the first.
 *
 * <p>Address presence (preconditions 3/4) is satisfied by the {@code order_address} row existing: the
 * schema makes {@code street_number/street/suburb/state_code/postcode} NOT NULL + non-blank, so an
 * existing row always has its required fields. Precondition 5 ("at least one priced line") is read
 * from the live summary subtotals (every line has a {@code line_total > 0} DB CHECK, so a non-zero
 * subtotal means ≥1 line). Precondition 9 uses the live computed {@code final_sale_price_inc_gst}
 * (there is no persisted {@code order_financial_summary} table — it is recomputed each time).
 */
@Component
public class InvoicePreconditionValidator {

    private static final String ADDRESS_INSTALLATION = "INSTALLATION";
    private static final String ADDRESS_BILLING = "BILLING";

    /**
     * Collect a detail for each failing precondition. Empty list = all 9 pass.
     *
     * @param order     the scoped sales order (details_of_sale / proposed_lay_date / lay_date_status)
     * @param customer  the order's customer row, or {@code null} if none saved yet
     * @param addresses the order's address rows (INSTALLATION / BILLING)
     * @param summary   the live financial summary (subtotals + final_sale_price_inc_gst)
     */
    public List<ErrorDetail> collectFailures(SalesOrder order,
                                             OrderCustomer customer,
                                             List<OrderAddress> addresses,
                                             OrderFinancialSummaryDto summary) {
        List<ErrorDetail> errors = new ArrayList<>();

        // 1 + 2. Customer first / last name present and non-blank.
        if (customer == null || isBlank(customer.getFirstName())) {
            errors.add(new ErrorDetail("customer", "first_name", "Customer first name is required."));
        }
        if (customer == null || isBlank(customer.getLastName())) {
            errors.add(new ErrorDetail("customer", "last_name", "Customer last name is required."));
        }

        // 3 + 4. Installation / billing address rows exist (required fields enforced by the schema).
        if (!hasAddress(addresses, ADDRESS_INSTALLATION)) {
            errors.add(new ErrorDetail("address", "installation_address", "Installation address is required."));
        }
        if (!hasAddress(addresses, ADDRESS_BILLING)) {
            errors.add(new ErrorDetail("address", "billing_address", "Billing address is required."));
        }

        // 5. At least one priced product or charge line (subtotal > 0 implies ≥1 line).
        boolean hasPricedLine = summary.productSubtotal().signum() > 0 || summary.chargeSubtotal().signum() > 0;
        if (!hasPricedLine) {
            errors.add(new ErrorDetail("lines", "lines", "At least one priced product or charge line is required."));
        }

        // 6. Details of sale present and non-blank.
        if (isBlank(order.getDetailsOfSale())) {
            errors.add(new ErrorDetail("details", "details_of_sale", "Details of sale is required."));
        }

        // 7. Proposed lay date present.
        if (order.getProposedLayDate() == null) {
            errors.add(new ErrorDetail("details", "proposed_lay_date", "Proposed lay date is required."));
        }

        // 8. Lay date status present.
        if (isBlank(order.getLayDateStatus())) {
            errors.add(new ErrorDetail("details", "lay_date_status", "Lay date status is required."));
        }

        // 9. Live final sale price (inc GST) > 0.
        if (summary.finalSalePriceIncGst() == null || summary.finalSalePriceIncGst().signum() <= 0) {
            errors.add(new ErrorDetail("financial", "final_sale_price_inc_gst",
                    "Final sale price must be greater than zero."));
        }

        return errors;
    }

    private static boolean hasAddress(List<OrderAddress> addresses, String addressType) {
        return addresses.stream().anyMatch(a -> addressType.equals(a.getAddressType()));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
