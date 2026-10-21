package com.andrewcorp.webwallet.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.TestClock;
import com.andrewcorp.webwallet.common.util.Zones;
import com.andrewcorp.webwallet.user.User;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

class EmailDigestsTest extends AbstractIntegrationTest {

    private static final Pattern UNSUBSCRIBE = Pattern.compile("<(https?://[^>]+)>");

    @Autowired
    private EmailDigests digests;

    @Autowired
    private NotificationSettingsService settings;

    private User user;
    private String auth;
    private Long card;
    private Long food;

    @BeforeEach
    void setUp() throws Exception {
        user = createVerifiedUser();
        auth = bearer(login(user.getEmail(), PASSWORD));
        card = createAccount(auth, "Card", "CARD", "UAH", 100000);
        food = createCategory(auth, "Food", "EXPENSE", null);
        when(mailSender.createMimeMessage())
                .thenAnswer(invocation -> new MimeMessage(Session.getInstance(new Properties())));
        clearInvocations(mailSender);
    }

    @Test
    void weeklyDigestComparesTheMonthWithAChartOnce() throws Exception {
        expense(LocalDate.of(2026, 9, 3), 1000);
        expense(LocalDate.of(2026, 10, 3), 1500);
        at(2026, 10, 19, 9);

        digests.send();
        digests.send();

        MimeMessage mail = sentMessage();
        assertThat(mail.getSubject()).isEqualTo("WebWallet: spending 1–18 October — 50 % more");
        assertThat(mail.getHeader("List-Unsubscribe-Post")[0]).isEqualTo("List-Unsubscribe=One-Click");
        assertThat(mail.getHeader("List-Unsubscribe")[0])
                .contains("/api/v1/notifications/unsubscribe?user=" + user.getId());
        String content = content(mail);
        assertThat(content)
                .contains("Spending 1–18 October")
                .contains("50 % more than 1–18 September")
                .contains("Food")
                .contains("cid:chart")
                .contains("/unsubscribe?user=" + user.getId());
    }

    @Test
    void monthlyDigestOnTheFirst() throws Exception {
        expense(LocalDate.of(2026, 9, 3), 1000);
        expense(LocalDate.of(2026, 10, 3), 800);
        at(2026, 11, 1, 9);

        digests.send();

        MimeMessage mail = sentMessage();
        assertThat(mail.getSubject()).isEqualTo("WebWallet: October summary — saved −800 ₴");
        assertThat(content(mail)).contains("October summary").contains("−20 %");
    }

    @Test
    void switchedOffOrOtherHourSendsNothing() throws Exception {
        expense(LocalDate.of(2026, 10, 3), 1500);
        at(2026, 10, 19, 8);
        digests.send();

        Map<String, Object> body =
                new HashMap<>(json(get("/api/v1/notifications/settings").header("Authorization", auth), 200));
        body.put("emailWeekly", false);
        json(putJson("/api/v1/notifications/settings", body, auth), 200);
        at(2026, 10, 19, 9);
        digests.send();

        verify(mailSender, never()).send(any(MimeMessage.class));
    }

    @Test
    void oneClickUnsubscribesAndTheLinkSubscribesAgain() throws Exception {
        expense(LocalDate.of(2026, 10, 3), 1500);
        at(2026, 10, 19, 9);
        digests.send();
        Matcher link = UNSUBSCRIBE.matcher(sentMessage().getHeader("List-Unsubscribe")[0]);
        assertThat(link.find()).isTrue();
        String query = link.group(1).substring(link.group(1).indexOf('?'));

        mockMvc.perform(post("/api/v1/notifications/unsubscribe" + query)).andExpect(status().isNoContent());

        assertThat(settings.get(user.getId()).emailWeekly()).isFalse();
        assertThat(settings.get(user.getId()).emailMonthly()).isFalse();
        mockMvc.perform(get("/api/v1/notifications/unsubscribe" + query))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/unsubscribe" + query)));
        mockMvc.perform(post("/api/v1/notifications/resubscribe" + query)).andExpect(status().isNoContent());
        assertThat(settings.get(user.getId()).emailWeekly()).isTrue();
    }

    @Test
    void linkOfAnotherUserDoesNothing() throws Exception {
        User other = createVerifiedUser();

        mockMvc.perform(post("/api/v1/notifications/unsubscribe?user=" + other.getId() + "&token=forged"))
                .andExpect(status().isNotFound());

        assertThat(settings.get(other.getId()).emailWeekly()).isTrue();
    }

    // helpers

    private static void at(int year, int month, int day, int hour) {
        TestClock.INSTANCE.set(
                LocalDateTime.of(year, month, day, hour, 0).atZone(Zones.KYIV).toInstant());
    }

    private void expense(LocalDate date, double amount) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of(
                "type", "EXPENSE", "date", date.toString(), "accountId", card, "amount", amount, "categoryId", food));
        json(postJson("/api/v1/transactions", body, auth), 201);
    }

    private MimeMessage sentMessage() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(1)).send(captor.capture());
        return captor.getValue();
    }

    /** All text parts of the email, HTML and plain. */
    private static String content(Part part) throws Exception {
        Object content = part.getContent();
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof Multipart multipart) {
            StringBuilder all = new StringBuilder();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart body = multipart.getBodyPart(i);
                if (body.isMimeType("image/*")) {
                    all.append("[image]");
                } else {
                    all.append(content(body));
                }
            }
            return all.toString();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        part.getDataHandler().writeTo(out);
        return out.toString();
    }
}
