package com.andrewCorp.webWallet.web.controller;

import com.andrewCorp.webWallet.persistence.dao.AccountRepository;
import com.andrewCorp.webWallet.persistence.model.Account;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("accounts")
public class AccountRestController {

    @Autowired
    private AccountRepository accountRepository;

    @GetMapping
    public List<Account> getAll(){
        return accountRepository.findAll();
    }

    @GetMapping("{id}")
    public Account getById(@PathVariable String id) {
        return accountRepository.getReferenceById(Long.parseLong(id));
    }

    @PostMapping
    public List<Account> create(@RequestBody Account account) {
        accountRepository.save(account);

        return accountRepository.findAll();
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
        accountRepository.delete(accountRepository.getReferenceById(Long.getLong(id)));
    }
}
