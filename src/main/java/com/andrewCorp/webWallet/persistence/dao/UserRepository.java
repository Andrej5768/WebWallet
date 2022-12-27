package com.andrewCorp.webWallet.persistence.dao;

import com.andrewCorp.webWallet.persistence.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {
    User findByEmail(String email);

    User save(User user);

    @Override
    void delete(User user);

}
