package com.andrewcorp.webwallet.bank.monobank;

import com.andrewcorp.webwallet.bank.monobank.MonobankClient.StatementItem;
import com.andrewcorp.webwallet.bank.monobank.dto.MonobankDtos.ConnectRequest;
import com.andrewcorp.webwallet.bank.monobank.dto.MonobankDtos.ConnectionResponse;
import com.andrewcorp.webwallet.bank.monobank.dto.MonobankDtos.LinkRequest;
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
                    e.getMostSpecificCause().getMessage());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WebhookEvent(String type, WebhookData data) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WebhookData(String account, StatementItem statementItem) {}
}
