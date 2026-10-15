package com.andrewcorp.webwallet.user;

import com.andrewcorp.webwallet.budget.BudgetRepository;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.error.ErrorCode;
import com.andrewcorp.webwallet.common.util.Texts;
import com.andrewcorp.webwallet.user.dto.UpdateProfileRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;
    private final BudgetRepository budget;

    public UserService(UserRepository users, BudgetRepository budget) {
        this.users = users;
        this.budget = budget;
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
        user.setLastName(Texts.trimToNull(request.lastName()));
        user.setLocale(request.locale());
        // Budget assignments are amounts in the base currency: they would silently change their value
        if (!user.getBaseCurrency().equals(request.baseCurrency()) && budget.existsByUserId(id)) {
            throw ApiException.invalid("baseCurrency", "error.user.baseCurrencyInUse");
        }
        user.setBaseCurrency(request.baseCurrency());
        return user;
    }
}
