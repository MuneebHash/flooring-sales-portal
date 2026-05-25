package com.flooring.salesportal.common.session;

import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.ForbiddenException;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.error.UnauthorizedException;
import com.flooring.salesportal.store.StoreRepository;
import com.flooring.salesportal.tenant.Business;
import com.flooring.salesportal.tenant.BusinessSlugResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;

@Component
public class RequestContextGuard {

    private final BusinessSlugResolver businessSlugResolver;
    private final StoreRepository storeRepository;

    public RequestContextGuard(BusinessSlugResolver businessSlugResolver,
                               StoreRepository storeRepository) {
        this.businessSlugResolver = businessSlugResolver;
        this.storeRepository = storeRepository;
    }

    public RequestContext requireStandardProtected(String slug, HttpServletRequest httpRequest) {
        // Resolve business from URL slug first. Unknown or inactive → 404.
        Business business = businessSlugResolver.resolveActive(slug);

        HttpSession session = httpRequest.getSession(false);
        if (session == null) {
            throw new UnauthorizedException(
                    ErrorCode.UNAUTHORIZED,
                    ErrorCode.UNAUTHORIZED.defaultMessage());
        }

        Long sessionUserId = SessionContext.userId(session);
        Long sessionBusinessId = SessionContext.businessId(session);
        if (sessionUserId == null || sessionBusinessId == null) {
            throw new UnauthorizedException(
                    ErrorCode.UNAUTHORIZED,
                    ErrorCode.UNAUTHORIZED.defaultMessage());
        }

        // Cross-tenant access never confirms the other tenant exists.
        if (!business.getBusinessId().equals(sessionBusinessId)) {
            throw new NotFoundException(ErrorCode.NOT_FOUND, "Business not found.");
        }

        Integer sessionStoreId = SessionContext.storeId(session);
        if (sessionStoreId == null) {
            throw new ForbiddenException(
                    ErrorCode.FORBIDDEN,
                    ErrorCode.FORBIDDEN.defaultMessage());
        }

        if (!storeRepository.existsActiveStoreInBusiness(business.getBusinessId(), sessionStoreId)
                || !storeRepository.userHasStoreAccess(business.getBusinessId(), sessionUserId, sessionStoreId)) {
            throw new ForbiddenException(
                    ErrorCode.FORBIDDEN,
                    ErrorCode.FORBIDDEN.defaultMessage());
        }

        return new RequestContext(business, sessionUserId, sessionStoreId);
    }
}
