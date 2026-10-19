package com.andrewcorp.webwallet.tax;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TaxSettingsRepository extends JpaRepository<TaxSettings, TaxSettings.Key> {}
