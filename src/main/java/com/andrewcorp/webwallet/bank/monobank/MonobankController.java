package com.andrewcorp.webwallet.bank.monobank;

import com.andrewcorp.webwallet.bank.monobank.MonobankClient.StatementItem;
import com.andrewcorp.webwallet.bank.monobank.dto.MonobankDtos.ConnectRequest;
import com.andrewcorp.webwallet.bank.monobank.dto.MonobankDtos.ConnectionResponse;
import com.andrewcorp.webwallet.bank.monobank.dto.MonobankDtos.LinkRequest;
import com.andrewcorp.webwallet.bank.monobank.dto.MonobankDtos.ProviderAuthResponse;
import com.andrewcorp.webwallet.bank.monobank.dto.MonobankDtos.ProviderConnectRequest;
import com.andrewcorp.webwallet.bank.monobank.dto.MonobankDtos.ScheduleRequest;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Monobank")
@RestController
@RequestMapping("/api/v1/banks/monobank")
public class MonobankController {

    private static final Logger log = LoggerFactory.getLogger(MonobankController.class);

    private final MonobankService monobank;

    public MonobankController(MonobankService monobank) {
        this.monobank = monobank;
    }

    @Operation(summary = "Connect Monobank with a personal token; operations are then imported in the background")
    @SecurityRequirement(name = "bearer")
    @PostMapping
    public ConnectionResponse connect(@CurrentUserId Long userId, @Valid @RequestBody ConnectRequest request) {
        return monobank.connect(userId, request);
    }

    @Operation(
            summary = "Connect Monobank through the bank app",
            description =
                    "Available when the server is a registered Monobank service provider (features.monobankProvider"
                            + " of the app configuration), otherwise 404. Show acceptUrl as a QR code, or open it on a phone;"
                            + " then poll the status until the user approves the access in the bank app (within 24 hours).")
    @SecurityRequirement(name = "bearer")
    @PostMapping("/auth")
    public ProviderAuthResponse startProviderAuth(
            @CurrentUserId Long userId, @Valid @RequestBody ProviderConnectRequest request) {
        return monobank.startProviderAuth(userId, request);
    }

    @Operation(summary = "Status of a connection through the bank app; APPROVED carries the new connection")
    @SecurityRequirement(name = "bearer")
    @GetMapping("/auth/{id}")
    public ProviderAuthResponse providerAuthStatus(@CurrentUserId Long userId, @PathVariable Long id) {
        return monobank.providerAuthStatus(userId, id);
    }

    /** The bank calls this once the user approved the access, with the id of the access request in a header. */
    @Hidden
    @GetMapping("/auth/callback/{secret}")
    public void providerAuthCallback(
            @PathVariable String secret, @RequestHeader(name = "X-Request-Id", required = false) String requestId) {
        try {
            monobank.providerAuthCallback(secret, requestId);
        } catch (ApiException e) {
            // The app asks the bank itself while it waits
            log.warn("Monobank access callback failed: {}", e.code());
        }
    }

    @Operation(summary = "Connection status, linked accounts and synchronization progress")
    @SecurityRequirement(name = "bearer")
    @GetMapping
    public ConnectionResponse status(@CurrentUserId Long userId) {
        return monobank.status(userId);
    }

    @Operation(summary = "Link a Monobank card or jar to another account, or stop synchronizing it with a null account")
    @SecurityRequirement(name = "bearer")
    @PutMapping("/accounts/{linkId}")
    public ConnectionResponse link(
            @CurrentUserId Long userId, @PathVariable Long linkId, @RequestBody LinkRequest request) {
        return monobank.link(userId, linkId, request.accountId());
    }

    @Operation(
            summary = "Change when Monobank is synchronized",
            description = "INTERVAL: every 15 minutes from windowStart to windowEnd (Kyiv time) and once at night"
                    + " halfway between windowEnd and windowStart; DAILY: once a day at dailyAt; WEBHOOK: the bank"
                    + " pushes new operations (needs the public URL of the server) and the accounts are also checked"
                    + " once a day at dailyAt; MANUAL: only on request. Omitted times are kept.")
    @SecurityRequirement(name = "bearer")
    @PutMapping("/schedule")
    public ConnectionResponse schedule(@CurrentUserId Long userId, @Valid @RequestBody ScheduleRequest request) {
        return monobank.schedule(userId, request);
    }

    @Operation(
            summary = "Synchronize now",
            description = "The accounts are requested in the background as soon as the bank allows the next request"
                    + " (one a minute), see nextRequestAt; in any mode.")
    @SecurityRequirement(name = "bearer")
    @PostMapping("/sync")
    public ConnectionResponse syncNow(@CurrentUserId Long userId) {
        return monobank.syncNow(userId);
    }

    @Operation(summary = "Disconnect Monobank; imported transactions stay")
    @SecurityRequirement(name = "bearer")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disconnect(@CurrentUserId Long userId) {
        monobank.disconnect(userId);
    }

    /** Monobank checks the webhook URL with a GET request before using it. */
    @Hidden
    @GetMapping("/webhook/{secret}")
    public void verifyWebhook(@PathVariable String secret) {
        // Nothing to do: Monobank only expects 200
    }

    /** Always answers 200 for a well-formed event: Monobank retries failed calls and then disables the webhook. */
    @Hidden
    @PostMapping("/webhook/{secret}")
    public void webhook(@PathVariable String secret, @RequestBody WebhookEvent event) {
        if (!"StatementItem".equals(event.type())
                || event.data() == null
                || event.data().statementItem() == null) {
            return;
        }
        try {
            monobank.handleWebhook(secret, event.data().account(), event.data().statementItem());
        } catch (DataIntegrityViolationException e) {
            // The operation was imported concurrently; the synchronization picks up anything missed
            log.warn(
                    "Monobank webhook operation was not imported: {}",
                    e.getMostSpecificCause().getClass().getSimpleName());
        }
    }

    /** The bank checks the webhook of the service with a test call before using it. */
    @Hidden
    @GetMapping("/webhook/provider/{secret}")
    public void verifyProviderWebhook(@PathVariable String secret) {
        // Nothing to do: the bank only expects 200
    }

    /** Operations of every user who gave the service access; answered with 200 for the same reason as above. */
    @Hidden
    @PostMapping("/webhook/provider/{secret}")
    public void providerWebhook(@PathVariable String secret, @RequestBody(required = false) WebhookEvent event) {
        if (event == null
                || !"StatementItem".equals(event.type())
                || event.data() == null
                || event.data().statementItem() == null) {
            return;
        }
        try {
            monobank.handleProviderWebhook(
                    secret, event.data().account(), event.data().statementItem());
        } catch (DataIntegrityViolationException e) {
            log.warn(
                    "Monobank webhook operation was not imported: {}",
                    e.getMostSpecificCause().getClass().getSimpleName());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WebhookEvent(String type, WebhookData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WebhookData(String account, StatementItem statementItem) {}
}
