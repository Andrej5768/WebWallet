package com.andrewcorp.webwallet.telegram;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelegramLinkRepository extends JpaRepository<TelegramLink, Long> {

    Optional<TelegramLink> findByChatId(long chatId);
}
