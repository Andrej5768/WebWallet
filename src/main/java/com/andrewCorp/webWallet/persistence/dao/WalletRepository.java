package com.andrewCorp.webWallet.persistence.dao;

import com.andrewCorp.webWallet.persistence.model.Account;
import com.andrewCorp.webWallet.persistence.model.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WalletRepository extends JpaRepository<Wallet, Long> {
    Optional<Wallet> findById(Long id);

    Wallet save(Wallet wallet);

    @Override
    void delete(Wallet wallet);
}
