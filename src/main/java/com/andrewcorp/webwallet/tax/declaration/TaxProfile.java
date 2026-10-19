package com.andrewcorp.webwallet.tax.declaration;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** The taxpayer details of a user for the declarations, encrypted: see {@link TaxProfileService}. */
@Entity
@Table(name = "tax_profiles")
public class TaxProfile {

    @Id
    private Long userId;

    @Column(nullable = false)
    private String encryptedData;

    @Column(nullable = false)
    private Instant updatedAt;

    protected TaxProfile() {}

    public TaxProfile(Long userId) {
        this.userId = userId;
    }

    public String getEncryptedData() {
        return encryptedData;
    }

    public void setEncryptedData(String encryptedData, Instant updatedAt) {
        this.encryptedData = encryptedData;
        this.updatedAt = updatedAt;
    }
}
