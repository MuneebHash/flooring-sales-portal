package com.flooring.salesportal.auth;

import com.flooring.salesportal.auth.dto.LoginRequest;
import com.flooring.salesportal.auth.dto.LoginResponse;
import com.flooring.salesportal.auth.dto.SelectStoreRequest;
import com.flooring.salesportal.auth.dto.SelectStoreResponse;
import com.flooring.salesportal.auth.dto.StoreDto;
import com.flooring.salesportal.auth.dto.UserDto;
import com.flooring.salesportal.common.api.ApiResponse;
import com.flooring.salesportal.common.error.ConflictException;
import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.ForbiddenException;
import com.flooring.salesportal.common.error.NotFoundException;
import com.flooring.salesportal.common.error.UnauthorizedException;
import com.flooring.salesportal.common.session.SessionContext;
import com.flooring.salesportal.store.Store;
import com.flooring.salesportal.store.StoreRepository;
import com.flooring.salesportal.tenant.Business;
import com.flooring.salesportal.tenant.BusinessSlugResolver;
import com.flooring.salesportal.user.AppUser;
import com.flooring.salesportal.user.AppUserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AuthService {

    private static final String LOGIN_OK_SINGLE_STORE = "Login successful.";
    private static final String LOGIN_OK_STORE_SELECTION = "Login successful. Select a store to continue.";
    private static final String STORE_SELECTED_MESSAGE = "Store selected.";
    private static final String LOGOUT_MESSAGE = "Logged out.";
    private static final String SESSION_COOKIE_NAME = "SP_SESSION";

    private final BusinessSlugResolver businessSlugResolver;
    private final AppUserRepository appUserRepository;
    private final StoreRepository storeRepository;
    private final PasswordEncoder passwordEncoder;

    public AuthService(BusinessSlugResolver businessSlugResolver,
                       AppUserRepository appUserRepository,
                       StoreRepository storeRepository,
                       PasswordEncoder passwordEncoder) {
        this.businessSlugResolver = businessSlugResolver;
        this.appUserRepository = appUserRepository;
        this.storeRepository = storeRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public ApiResponse<LoginResponse> login(String slug,
                                            LoginRequest request,
                                            HttpServletRequest httpRequest) {
        Business business = businessSlugResolver.resolveActive(slug);

        String salespersonCode = request.salespersonCode().trim();
        AppUser user = appUserRepository
                .findByBusinessIdAndSalespersonCode(business.getBusinessId(), salespersonCode)
                .filter(AppUser::isActive)
                .filter(u -> passwordEncoder.matches(request.password(), u.getPasswordHash()))
                .orElseThrow(() -> new UnauthorizedException(
                        ErrorCode.INVALID_CREDENTIALS,
                        ErrorCode.INVALID_CREDENTIALS.defaultMessage()));

        List<Store> accessibleStores = storeRepository
                .findAccessibleActiveStores(business.getBusinessId(), user.getUserId());

        if (accessibleStores.isEmpty()) {
            throw new ForbiddenException(
                    ErrorCode.NO_STORE_ACCESS,
                    ErrorCode.NO_STORE_ACCESS.defaultMessage());
        }

        HttpSession session = httpRequest.getSession(true);
        // Rotate the session id before storing authenticated attributes so a pre-login
        // identifier cannot be reused to hijack the now-authenticated session (fixation).
        httpRequest.changeSessionId();
        session.setAttribute(SessionContext.USER_ID, user.getUserId());
        session.setAttribute(SessionContext.BUSINESS_ID, business.getBusinessId());
        // Drop any stale store_id from a reused session so a multi-store login can never
        // inherit a previously selected store. The single-store branch below re-sets it.
        session.removeAttribute(SessionContext.STORE_ID);

        Integer activeStoreId = null;
        boolean storeSelectionRequired = true;
        String message = LOGIN_OK_STORE_SELECTION;

        if (accessibleStores.size() == 1) {
            Store only = accessibleStores.get(0);
            session.setAttribute(SessionContext.STORE_ID, only.getStoreId());
            activeStoreId = only.getStoreId();
            storeSelectionRequired = false;
            message = LOGIN_OK_SINGLE_STORE;
        }

        LoginResponse body = new LoginResponse(
                toUserDto(user),
                accessibleStores.stream().map(this::toStoreDto).toList(),
                activeStoreId,
                storeSelectionRequired);

        return ApiResponse.ok(body, message);
    }

    public ApiResponse<SelectStoreResponse> selectStore(String slug,
                                                        SelectStoreRequest request,
                                                        HttpServletRequest httpRequest) {
        HttpSession session = requireSession(httpRequest);

        Long sessionUserId = SessionContext.userId(session);
        Long sessionBusinessId = SessionContext.businessId(session);
        if (sessionUserId == null || sessionBusinessId == null) {
            throw new UnauthorizedException(
                    ErrorCode.UNAUTHORIZED,
                    ErrorCode.UNAUTHORIZED.defaultMessage());
        }

        Business business = businessSlugResolver.resolveActive(slug);

        if (!business.getBusinessId().equals(sessionBusinessId)) {
            throw new NotFoundException(ErrorCode.NOT_FOUND, "Business not found.");
        }

        Integer requestedStoreId = request.storeId();

        if (!storeRepository.existsActiveStoreInBusiness(business.getBusinessId(), requestedStoreId)) {
            throw new NotFoundException(ErrorCode.NOT_FOUND, "Store not found.");
        }

        if (!storeRepository.userHasStoreAccess(business.getBusinessId(), sessionUserId, requestedStoreId)) {
            throw new ForbiddenException(ErrorCode.FORBIDDEN, "Access to this store is not permitted.");
        }

        // Load entities for the response BEFORE touching the session, so a failed lookup
        // cannot leave the session half-mutated (e.g. store_id written while user load
        // fails). The session is only written once every read has succeeded and the
        // conflict check has cleared.
        Store store = storeRepository.findById(requestedStoreId)
                .orElseThrow(() -> new NotFoundException(ErrorCode.NOT_FOUND, "Store not found."));
        AppUser user = appUserRepository.findById(sessionUserId)
                .orElseThrow(() -> new UnauthorizedException(
                        ErrorCode.UNAUTHORIZED,
                        ErrorCode.UNAUTHORIZED.defaultMessage()));

        Integer currentStoreId = SessionContext.storeId(session);
        if (currentStoreId != null && !currentStoreId.equals(requestedStoreId)) {
            throw new ConflictException(
                    ErrorCode.STORE_ALREADY_SELECTED,
                    ErrorCode.STORE_ALREADY_SELECTED.defaultMessage());
        }

        if (currentStoreId == null) {
            session.setAttribute(SessionContext.STORE_ID, requestedStoreId);
        }

        SelectStoreResponse body = new SelectStoreResponse(toUserDto(user), toStoreDto(store));
        return ApiResponse.ok(body, STORE_SELECTED_MESSAGE);
    }

    public ApiResponse<Void> logout(String slug,
                                    HttpServletRequest httpRequest,
                                    HttpServletResponse httpResponse) {
        HttpSession session = requireSession(httpRequest);

        Long sessionBusinessId = SessionContext.businessId(session);
        if (SessionContext.userId(session) == null || sessionBusinessId == null) {
            throw new UnauthorizedException(
                    ErrorCode.UNAUTHORIZED,
                    ErrorCode.UNAUTHORIZED.defaultMessage());
        }

        Business business = businessSlugResolver.resolveActive(slug);

        if (!business.getBusinessId().equals(sessionBusinessId)) {
            throw new NotFoundException(ErrorCode.NOT_FOUND, "Business not found.");
        }

        session.invalidate();

        ResponseCookie expired = ResponseCookie.from(SESSION_COOKIE_NAME, "")
                .path("/")
                .httpOnly(true)
                .sameSite("Lax")
                .maxAge(0)
                .build();
        httpResponse.addHeader(HttpHeaders.SET_COOKIE, expired.toString());

        return ApiResponse.<Void>ok(null, LOGOUT_MESSAGE);
    }

    private HttpSession requireSession(HttpServletRequest httpRequest) {
        HttpSession session = httpRequest.getSession(false);
        if (session == null) {
            throw new UnauthorizedException(
                    ErrorCode.UNAUTHORIZED,
                    ErrorCode.UNAUTHORIZED.defaultMessage());
        }
        return session;
    }

    private UserDto toUserDto(AppUser user) {
        return new UserDto(
                user.getUserId(),
                user.getFirstName(),
                user.getLastName(),
                user.getSalespersonCode());
    }

    private StoreDto toStoreDto(Store store) {
        return new StoreDto(
                store.getStoreId(),
                store.getName(),
                store.getStoreCode(),
                store.getPhone(),
                store.getEmail(),
                store.getStreet(),
                store.getSuburb(),
                store.getStateCode(),
                store.getPostcode());
    }
}
