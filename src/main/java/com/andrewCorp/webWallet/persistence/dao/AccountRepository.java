package com.andrewCorp.webWallet.persistence.dao;

import com.andrewCorp.webWallet.persistence.model.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;

public interface AccountRepository extends JpaRepository<Account, Long> {
    Account findByNameAndWalletId(String name, Long walletId);

    List<Account> findAllByWalletId(Long id);

    @Override
    void delete(Account entity);
}
