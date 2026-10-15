package com.andrewcorp.webwallet.user;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.ledger.category.DefaultCategories;
import com.andrewcorp.webwallet.user.dto.ResetRequest;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Start over": deletes the finance data of a user. The profile, the password and the sessions stay.
 */
@Service
public class UserDataService {

    private final UserService users;
    private final DefaultCategories defaultCategories;
    private final JdbcTemplate jdbc;

    public UserDataService(UserService users, DefaultCategories defaultCategories, JdbcTemplate jdbc) {
        this.users = users;
        this.defaultCategories = defaultCategories;
        this.jdbc = jdbc;
    }

    /**
     * Deletes the accounts, transactions (with their splits and tags), payees, tags, budget, bank connections and
     * import previews of the user, and, unless they are kept, the categories and categorization rules; deleted
     * categories are replaced by the default ones. Payees that kept rules refer to stay. All or nothing.
     */
    @Transactional
    public void reset(Long userId, ResetRequest request) {
        if (!ResetRequest.CONFIRMATION.equals(request.confirm())) {
            throw ApiException.invalid("confirm", "error.user.resetConfirm");
        }
        User user = users.get(userId);
        // Children before the rows they refer to; splits, transaction tags and bank account links go with their
        // parents (on delete cascade)
        delete("delete from import_previews where user_id = ?", userId);
        delete("delete from bank_connections where user_id = ?", userId);
        delete("delete from transactions where user_id = ?", userId);
        delete("delete from budget_assignments where user_id = ?", userId);
        delete("delete from accounts where user_id = ?", userId);
        delete("delete from tags where user_id = ?", userId);
        if (Boolean.TRUE.equals(request.keepCategories())) {
            delete("""
                    delete from payees p
                    where p.user_id = ? and not exists (select 1 from categorization_rules r where r.payee_id = p.id)""", userId);
            return;
        }
        delete("delete from categorization_rules where user_id = ?", userId);
        delete("delete from payees where user_id = ?", userId);
        delete("delete from categories where user_id = ?", userId);
        defaultCategories.create(userId, Locale.forLanguageTag(user.getLocale()));
    }

    private void delete(String sql, Long userId) {
        jdbc.update(sql, userId);
    }
}
