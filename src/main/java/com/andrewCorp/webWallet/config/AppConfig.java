package com.andrewCorp.webWallet.config;

import com.andrewCorp.webWallet.security.ActiveUserStore;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Configuration
public class AppConfig {
    // beans

    @Bean
    public ActiveUserStore activeUserStore() {
        return new ActiveUserStore();
    }

    // There is no base messages.properties, so Spring Boot does not auto-configure a MessageSource
    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("messages");
        messageSource.setDefaultEncoding(StandardCharsets.UTF_8.name());
        messageSource.setFallbackToSystemLocale(false);
        messageSource.setDefaultLocale(Locale.ENGLISH);
        return messageSource;
    }

}
