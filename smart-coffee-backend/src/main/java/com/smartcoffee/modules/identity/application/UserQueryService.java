package com.smartcoffee.modules.identity.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.i18n.LocaleNormalizer;
import com.smartcoffee.modules.identity.api.UpdateUserProfileRequest;
import com.smartcoffee.modules.identity.api.UserResponse;
import com.smartcoffee.modules.identity.domain.User;
import com.smartcoffee.modules.identity.persistence.UserRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserQueryService {
    private final UserRepository users;

    public UserQueryService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public UserResponse me(UUID id) {
        var u = users.findById(id).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return new UserResponse(u.id(), u.fullName(), u.email(), u.preferredLanguage(), u.status().name());
    }

    /**
     * Applies a partial self-profile update. A null request field leaves the current
     * value untouched (validation in the DTO already rejected any supplied malformed
     * value). Ownership is the caller-provided authenticated actor id; there is no
     * id parameter on the endpoint. Delegates persistence to {@link User#updateProfile}.
     */
    @Transactional
    public UserResponse update(UUID id, UpdateUserProfileRequest request) {
        User u = users.findById(id).orElseThrow(() -> new ResourceNotFoundException("User not found"));
        String fullName = request.fullName() != null ? request.fullName() : u.fullName();
        String phone = request.phone() != null ? request.phone() : u.phone();
        String language = request.preferredLanguage() != null
                ? LocaleNormalizer.normalize(request.preferredLanguage())
                : u.preferredLanguage();
        u.updateProfile(fullName, phone, language);
        return new UserResponse(u.id(), u.fullName(), u.email(), u.preferredLanguage(), u.status().name());
    }
}
