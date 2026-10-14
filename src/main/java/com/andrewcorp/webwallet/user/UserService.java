package com.andrewcorp.webwallet.user;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.user.dto.UpdateProfileRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    /** The access token may outlive a deleted user, which is treated as unauthenticated. */
    @Transactional(readOnly = true)
    public User get(Long id) {
        return users.findById(id).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHORIZED));
    }

    @Transactional
    public User updateProfile(Long id, UpdateProfileRequest request) {
        User user = get(id);
        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName().trim());
        user.setLocale(request.locale());
        return user;
    }
}
