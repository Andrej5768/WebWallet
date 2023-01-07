package com.andrewCorp.webWallet.persistence.dao;

import com.andrewCorp.webWallet.persistence.model.Category;
import com.andrewCorp.webWallet.persistence.model.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    Category findByName(String name);

    Optional<Category> findById(Long id);

    List<Category> findAllByWalletId(Long id);

    @Override
    void delete(Category entity);
}
