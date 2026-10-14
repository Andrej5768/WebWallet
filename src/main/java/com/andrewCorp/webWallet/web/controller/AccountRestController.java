package com.andrewCorp.webWallet.web.controller;

import com.andrewCorp.webWallet.persistence.dao.AccountRepository;
import com.andrewCorp.webWallet.persistence.model.Account;
import com.andrewCorp.webWallet.persistence.model.User;
import com.andrewCorp.webWallet.persistence.model.Wallet;
import com.andrewCorp.webWallet.service.IUserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Objects;

@RestController
@RequestMapping("accounts")
public class AccountRestController {

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private IUserService userService;

    @GetMapping
    public List<Account> getAll(){
        return accountRepository.findAllByWalletId(getWalletFromContext().getId());
    }

    @GetMapping("{id}")
    public Account getById(@PathVariable String id) {
        return getOwnedAccount(Long.parseLong(id), getWalletFromContext());
    }

    @PostMapping
    public List<Account> create(@RequestBody Account account) {
        Wallet wallet = getWalletFromContext();
        account.setId(null);
        account.setWallet(wallet);
        accountRepository.save(account);

        return accountRepository.findAllByWalletId(wallet.getId());
    }

    //TODO
//    @PutMapping("{id}")
//    public List<Account> update(@PathVariable String id, @RequestBody Account account) {
//        Account accountFromDb = accountRepository.getReferenceById(Long.getLong(id));
//        accountFromDb = account;
//
//        return accountRepository.findAll();
//    }

    @DeleteMapping("{id}")
    public void delete(@PathVariable String id) {
        accountRepository.delete(getOwnedAccount(Long.parseLong(id), getWalletFromContext()));
    }

    private Wallet getWalletFromContext() {
        final User user = userService.findUserByEmail(((User) SecurityContextHolder.getContext().getAuthentication().getPrincipal()).getEmail());
        return user.getWallet();
    }

    // Responds with 404 instead of 403 to not reveal that the account exists
    private Account getOwnedAccount(Long id, Wallet wallet) {
        return accountRepository.findById(id)
                .filter(account -> account.getWallet() != null && Objects.equals(account.getWallet().getId(), wallet.getId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
