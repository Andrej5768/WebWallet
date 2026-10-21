package com.andrewcorp.webwallet.telegram;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelegramLinkCodeRepository extends JpaRepository<TelegramLinkCode, Long> {

    Optional<TelegramLinkCode> findByCodeHash(String codeHash);
}
