package com.andrewcorp.webwallet.tax.declaration;

import com.andrewcorp.webwallet.common.crypto.SecretCipher;
import java.time.Clock;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** The taxpayer details of a user, kept encrypted as one JSON text: the tax number is personal data. */
@Service
public class TaxProfileService {

    private final TaxProfileRepository profiles;
    private final SecretCipher cipher;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public TaxProfileService(TaxProfileRepository profiles, SecretCipher cipher, JsonMapper jsonMapper, Clock clock) {
        this.profiles = profiles;
        this.cipher = cipher;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<TaxProfileData> find(Long userId) {
        return profiles.findById(userId)
                .map(profile -> jsonMapper.readValue(cipher.decrypt(profile.getEncryptedData()), TaxProfileData.class));
    }

    @Transactional
    public TaxProfileData save(Long userId, TaxProfileData data) {
        TaxProfile profile = profiles.findById(userId).orElseGet(() -> new TaxProfile(userId));
        profile.setEncryptedData(cipher.encrypt(jsonMapper.writeValueAsString(data)), clock.instant());
        profiles.save(profile);
        return data;
    }
}
