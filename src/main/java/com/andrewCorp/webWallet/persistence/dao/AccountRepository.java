package com.andrewCorp.webWallet.persistence.dao;

import com.andrewCorp.webWallet.persistence.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AccountRepository extends JpaRepository<Account, Long> {
    Account findByName(String name);
    List<Account> findAccountsByUserId(Long id);


    List<Account> findAll();
    @Override
    void delete(Account entity);
}
