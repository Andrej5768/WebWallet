package com.andrewcorp.webwallet.notification;

import com.andrewcorp.webwallet.common.error.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The links of the digest emails, which work without signing in. */
@Tag(name = "Notifications")
@RestController
public class UnsubscribeController {

    private final DigestLinks links;
    private final NotificationSettingsService settings;

    public UnsubscribeController(DigestLinks links, NotificationSettingsService settings) {
        this.links = links;
        this.settings = settings;
    }

    @Operation(
            summary = "Unsubscribe from the email digest",
            description = "By the signed link of a digest email; also the one-click unsubscribe of the mail client."
                    + " NOT_FOUND for a link that is not valid.")
    @PostMapping(DigestLinks.UNSUBSCRIBE_PATH)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@RequestParam Long user, @RequestParam String token) {
        check(user, token);
        settings.setDigest(user, false);
    }

    @Operation(summary = "Subscribe to the email digest again, by the link of a digest email")
    @PostMapping("/api/v1/notifications/resubscribe")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resubscribe(@RequestParam Long user, @RequestParam String token) {
        check(user, token);
        settings.setDigest(user, true);
    }

    /** A mail client that opens the unsubscribe link in a browser gets the page that does it. */
    @Operation(summary = "Open the unsubscribe page of the web client")
    @GetMapping(DigestLinks.UNSUBSCRIBE_PATH)
    public ResponseEntity<Void> page(@RequestParam Long user, @RequestParam String token) {
        check(user, token);
        return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .location(URI.create(links.page(user)))
                .build();
    }

    private void check(Long user, String token) {
        if (!links.valid(user, token)) {
            throw ApiException.notFound();
        }
    }
}
