package com.flooring.salesportal.common.session;

import com.flooring.salesportal.tenant.Business;

public record RequestContext(Business business, Long userId, Integer storeId) {

    public Long businessId() {
        return business.getBusinessId();
    }
}
