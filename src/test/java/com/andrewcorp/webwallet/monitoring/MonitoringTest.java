package com.andrewcorp.webwallet.monitoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.TestClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {"app.rate-limit.requests-per-minute=5", "app.alerts.email=alerts@test.com"})
class MonitoringTest extends AbstractIntegrationTest {

    @Autowired
    private ErrorAlerts errorAlerts;

    @Test
    void limitsTheRequestsOfOneClient() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/v1/auth/options")).andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/v1/auth/options"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        // Another client is not affected
        mockMvc.perform(get("/api/v1/auth/options").with(request -> {
                    request.setRemoteAddr("10.0.0.2");
                    return request;
                }))
                .andExpect(status().isOk());
        // Health checks are not limited
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void emailsALoggedErrorAndSumsUpTheNextOnes() {
        LoggerFactory.getLogger("com.andrewcorp.webwallet.SomeTask")
                .error("Sync failed\nfor connection 7", new IllegalStateException("bank is down"));

        ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getTo()).containsExactly("alerts@test.com");
        assertThat(mail.getValue().getSubject()).isEqualTo("[WebWallet localhost] Sync failed");
        assertThat(mail.getValue().getText())
                .contains(
                        "com.andrewcorp.webwallet.SomeTask", "for connection 7", "IllegalStateException: bank is down");

        clearInvocations(mailSender);
        LoggerFactory.getLogger("com.andrewcorp.webwallet.SomeTask").error("Sync failed again");
        LoggerFactory.getLogger("com.andrewcorp.webwallet.SomeTask").warn("Only a warning");
        errorAlerts.flush();
        verify(mailSender, never()).send(any(SimpleMailMessage.class));

        TestClock.INSTANCE.set(Instant.now().plus(Duration.ofMinutes(16)));
        errorAlerts.flush();

        verify(mailSender).send(mail.capture());
        assertThat(mail.getValue().getSubject()).isEqualTo("[WebWallet localhost] 1 more errors");
        assertThat(mail.getValue().getText()).contains("Sync failed again");
    }
}
