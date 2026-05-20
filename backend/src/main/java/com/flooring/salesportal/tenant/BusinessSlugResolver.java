package com.flooring.salesportal.tenant;

import com.flooring.salesportal.common.error.ErrorCode;
import com.flooring.salesportal.common.error.NotFoundException;
import org.springframework.stereotype.Service;

@Service
public class BusinessSlugResolver {

    private final BusinessRepository businessRepository;

    public BusinessSlugResolver(BusinessRepository businessRepository) {
        this.businessRepository = businessRepository;
    }

    public Business resolveActive(String slug) {
        if (slug == null || slug.isBlank()) {
            throw notFound();
        }
        return businessRepository.findBySlug(slug)
                .filter(Business::isActive)
                .orElseThrow(this::notFound);
    }

    private NotFoundException notFound() {
        return new NotFoundException(ErrorCode.NOT_FOUND, "Business not found.");
    }
}
