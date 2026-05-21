package com.flooring.salesportal.common.session;

import jakarta.servlet.http.HttpSession;

public final class SessionContext {

    public static final String USER_ID = "user_id";
    public static final String BUSINESS_ID = "business_id";
    public static final String STORE_ID = "store_id";

    private SessionContext() {
    }

    public static Long userId(HttpSession session) {
        return (Long) session.getAttribute(USER_ID);
    }

    public static Long businessId(HttpSession session) {
        return (Long) session.getAttribute(BUSINESS_ID);
    }

    public static Integer storeId(HttpSession session) {
        return (Integer) session.getAttribute(STORE_ID);
    }
}
