package com.andrewcorp.webwallet.ledger.account;

import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.ledger.account.dto.AccountRequest;
import com.andrewcorp.webwallet.ledger.account.dto.AccountResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Accounts")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @Operation(summary = "List accounts with current balances")
    @GetMapping
    public List<AccountResponse> list(@CurrentUserId Long userId) {
        return accountService.list(userId);
    }

    @GetMapping("/{id}")
    public AccountResponse get(@CurrentUserId Long userId, @PathVariable Long id) {
        return accountService.get(userId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@CurrentUserId Long userId, @Valid @RequestBody AccountRequest request) {
        return accountService.create(userId, request);
    }

    @Operation(summary = "Update an account; the currency cannot be changed")
    @PutMapping("/{id}")
    public AccountResponse update(@CurrentUserId Long userId, @PathVariable Long id, @Valid @RequestBody AccountRequest request) {
        return accountService.update(userId, id, request);
    }

    @Operation(summary = "Delete an account without transactions; accounts with history should be archived")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId Long userId, @PathVariable Long id) {
        accountService.delete(userId, id);
    }
}
