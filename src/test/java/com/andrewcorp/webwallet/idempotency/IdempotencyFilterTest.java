package com.andrewcorp.webwallet.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andrewcorp.webwallet.AbstractIntegrationTest;
import com.andrewcorp.webwallet.auth.TokenCleanupTask;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class IdempotencyFilterTest extends AbstractIntegrationTest {

    @Autowired
    private IdempotencyKeyRepository keys;

    @Autowired
    private TokenCleanupTask cleanup;

    private String auth;
    private Long cash;

    @BeforeEach
    void setUp() throws Exception {
        auth = loginNewUser();
        cash = createAccount(auth, "Cash", "CASH", "UAH", 100);
    }

    @Test
    void theSameRequestSentAgainGetsTheFirstResponseAndCreatesNothing() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> first = json(expense(key, 10), 201);

        Map<String, Object> second = json(expense(key, 10), 201);
        mockMvc.perform(expense(key, 10)).andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"));

        assertThat(second).isEqualTo(first);
        assertThat(json(get("/api/v1/transactions").header("Authorization", auth), 200))
                .containsEntry("totalItems", 1);
    }

    @Test
    void theSameKeyWithAnotherRequestIsAConflict() throws Exception {
        String key = UUID.randomUUID().toString();
        json(expense(key, 10), 201);

        Map<String, Object> problem = json(expense(key, 20), 409);

        assertThat(problem).containsEntry("code", "IDEMPOTENCY_MISMATCH");
        assertThat(json(get("/api/v1/transactions").header("Authorization", auth), 200))
                .containsEntry("totalItems", 1);
    }

    @Test
    void aKeyBelongsToItsUser() throws Exception {
        String key = UUID.randomUUID().toString();
        Long mine = id(json(expense(key, 10), 201));
        String other = loginNewUser();
        Long otherCash = createAccount(other, "Cash", "CASH", "UAH", 0);

        Map<String, Object> body =
                Map.of("type", "EXPENSE", "date", "2026-10-01", "accountId", otherCash, "amount", 10);
        Long theirs =
                id(json(postJson("/api/v1/transactions", body, other).header(IdempotencyFilter.HEADER, key), 201));

        assertThat(theirs).isNotEqualTo(mine);
    }

    @Test
    void aFailedRequestLeavesItsKeyFree() throws Exception {
        String key = UUID.randomUUID().toString();
        Map<String, Object> invalid = Map.of("type", "EXPENSE", "date", "2026-10-01", "accountId", cash);
        json(postJson("/api/v1/transactions", invalid, auth).header(IdempotencyFilter.HEADER, key), 400);

        json(expense(key, 10), 201);
    }

    @Test
    void aKeyThatIsNotAUuidIsRejected() throws Exception {
        Map<String, Object> problem = json(expense("order-1", 10), 400);

        assertThat(problem).containsEntry("code", "INVALID_IDEMPOTENCY_KEY");
    }

    @Test
    void requestsWithoutAKeyAndOtherEndpointsAreNotAffected() throws Exception {
        Map<String, Object> body = Map.of("type", "EXPENSE", "date", "2026-10-01", "accountId", cash, "amount", 10);
        json(postJson("/api/v1/transactions", body, auth), 201);
        json(postJson("/api/v1/transactions", body, auth), 201);
        String key = UUID.randomUUID().toString();
        mockMvc.perform(get("/api/v1/transactions")
                        .header("Authorization", auth)
                        .header(IdempotencyFilter.HEADER, key))
                .andExpect(status().isOk());

        assertThat(json(get("/api/v1/transactions").header("Authorization", auth), 200))
                .containsEntry("totalItems", 2);
        assertThat(keys.count()).isZero();
    }

    @Test
    void aKeyOlderThanADayIsForgottenAndPurged() throws Exception {
        String key = UUID.randomUUID().toString();
        Long userId = users.findByEmail(currentEmail()).orElseThrow().getId();
        keys.save(new IdempotencyKey(
                userId,
                UUID.fromString(key),
                "stale",
                201,
                "application/json",
                "{\"id\":-1}",
                Instant.now().minus(IdempotencyFilter.RETENTION).minus(Duration.ofMinutes(1))));

        Map<String, Object> created = json(expense(key, 10), 201);
        assertThat(id(created)).isPositive();

        keys.save(new IdempotencyKey(
                userId,
                UUID.randomUUID(),
                "stale",
                201,
                null,
                "{}",
                Instant.now().minus(Duration.ofDays(2))));
        cleanup.purge();
        assertThat(keys.count()).isEqualTo(1);
    }

    private MockHttpServletRequestBuilder expense(String key, double amount) {
        Map<String, Object> body = Map.of("type", "EXPENSE", "date", "2026-10-01", "accountId", cash, "amount", amount);
        return postJson("/api/v1/transactions", body, auth).header(IdempotencyFilter.HEADER, key);
    }

    private String currentEmail() throws Exception {
        return (String)
                json(get("/api/v1/me").header("Authorization", auth), 200).get("email");
    }
}
