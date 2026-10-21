package com.andrewcorp.webwallet.telegram;

import com.andrewcorp.webwallet.auth.twofactor.TwoFactorService;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.telegram.TelegramLinkService.TelegramLinkResponse;
import com.andrewcorp.webwallet.telegram.TelegramLinkService.TelegramSettingsRequest;
import com.andrewcorp.webwallet.telegram.TelegramLinkService.TelegramStatus;
import com.andrewcorp.webwallet.user.dto.ConfirmIdentityRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notifications")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/telegram")
public class TelegramController {

    private final TelegramLinkService linkService;
    private final TelegramBot bot;
    private final TwoFactorService twoFactor;

    public TelegramController(TelegramLinkService linkService, TelegramBot bot, TwoFactorService twoFactor) {
        this.linkService = linkService;
        this.bot = bot;
        this.twoFactor = twoFactor;
    }

    @Operation(summary = "Whether the bot is available and the Telegram chat of the user, if connected")
    @GetMapping
    public TelegramStatus status(@CurrentUserId Long userId) {
        return linkService.status(userId);
    }

    @Operation(
            summary = "Create a one-time link that connects a Telegram chat",
            description = "The link opens the bot; pressing Start there connects the chat. It works once, for 15"
                    + " minutes; a new link replaces the previous one. Needs the current password, or for an account"
                    + " without one a two-factor code when it is on. NOT_FOUND when the bot is not configured.")
    @PostMapping("/link")
    public TelegramLinkResponse link(@CurrentUserId Long userId, @Valid @RequestBody ConfirmIdentityRequest request) {
        twoFactor.confirmIdentity(userId, request.password(), request.code());
        return linkService.createLink(userId);
    }

    @Operation(summary = "Settings of the connected chat", description = "NOT_FOUND when no chat is connected")
    @PutMapping("/settings")
    public TelegramStatus updateSettings(@CurrentUserId Long userId, @RequestBody TelegramSettingsRequest request) {
        return linkService.updateSettings(userId, request);
    }

    @Operation(summary = "Disconnect the Telegram chat")
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink(@CurrentUserId Long userId) {
        linkService.unlink(userId).ifPresent(chatId -> bot.sayUnlinked(chatId, userId));
    }
}
