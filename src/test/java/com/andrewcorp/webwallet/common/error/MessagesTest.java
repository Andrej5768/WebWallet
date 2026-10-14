package com.andrewcorp.webwallet.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class MessagesTest {

    private static Properties load(String name) throws Exception {
        Properties properties = new Properties();
        try (Reader reader =
                new InputStreamReader(new ClassPathResource(name).getInputStream(), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    @Test
    void bothLanguagesHaveTheSameKeys() throws Exception {
        assertThat(load("messages_uk.properties").stringPropertyNames())
                .containsExactlyInAnyOrderElementsOf(load("messages.properties").stringPropertyNames());
    }

    @Test
    void everyErrorCodeHasAMessage() throws Exception {
        Properties messages = load("messages.properties");

        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::messageKey))
                .allSatisfy(key -> assertThat(messages).containsKey(key));
    }
}
