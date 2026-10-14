package com.andrewcorp.webwallet.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    /** Expects an already normalized (lower case) email. */
    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);
}
