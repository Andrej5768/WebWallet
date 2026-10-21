package com.andrewcorp.webwallet.notification;

import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.notification.NotificationSettingsService.NotificationSettingsDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Notifications")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/notifications/settings")
public class NotificationSettingsController {

    private final NotificationSettingsService settings;

    public NotificationSettingsController(NotificationSettingsService settings) {
        this.settings = settings;
    }

    @Operation(summary = "What the user is notified about in Telegram, and at which hour")
    @GetMapping
    public NotificationSettingsDto get(@CurrentUserId Long userId) {
        return settings.get(userId);
    }

    @PutMapping
    public NotificationSettingsDto update(
            @CurrentUserId Long userId, @Valid @RequestBody NotificationSettingsDto request) {
        return settings.update(userId, request);
    }
}
