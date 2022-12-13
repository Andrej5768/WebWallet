package com.andrewCorp.webWallet.task;

import com.andrewCorp.webWallet.persistence.dao.PasswordResetTokenRepository;
import com.andrewCorp.webWallet.persistence.dao.VerificationTokenRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TokensPurgeTask {

    @Autowired
    VerificationTokenRepository tokenRepository;

    @Autowired
    PasswordResetTokenRepository passwordTokenRepository;

//    @Scheduled(cron = "${purge.cron.expression}")
//    public void purgeExpired() {
//
//        Date now = Date.from(Instant.now());
//
//        passwordTokenRepository.deleteAllExpiredSince(now);
//        tokenRepository.deleteAllExpiredSince(now);
//    }
}
