package com.flooring.salesportal.tenant;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessQuickDescriptionRepository
        extends JpaRepository<BusinessQuickDescription, Long> {

    /**
     * Quick-add descriptions for one business, in deterministic display order:
     * {@code sort_order} ascending, then {@code business_quick_description_id} ascending as a stable
     * tiebreak. The id tiebreak matters because {@code sort_order} has no unique constraint and
     * defaults to 0 (V12), so equal/duplicate sort_order values are possible.
     */
    List<BusinessQuickDescription> findByBusinessIdOrderBySortOrderAscBusinessQuickDescriptionIdAsc(
            Long businessId);
}
