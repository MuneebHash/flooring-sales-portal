package com.flooring.salesportal.auth;

import com.flooring.salesportal.auth.dto.LoginRequest;
import com.flooring.salesportal.auth.dto.LoginResponse;
import com.flooring.salesportal.auth.dto.SelectStoreRequest;
import com.flooring.salesportal.auth.dto.SelectStoreResponse;
import com.flooring.salesportal.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/{slug}/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@PathVariable String slug,
                                            @Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest) {
        return authService.login(slug, request, httpRequest);
    }

    @PostMapping("/select-store")
    public ApiResponse<SelectStoreResponse> selectStore(@PathVariable String slug,
                                                        @Valid @RequestBody SelectStoreRequest request,
                                                        HttpServletRequest httpRequest) {
        return authService.selectStore(slug, request, httpRequest);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@PathVariable String slug,
                                    HttpServletRequest httpRequest,
                                    HttpServletResponse httpResponse) {
        return authService.logout(slug, httpRequest, httpResponse);
    }
}
