package com.andrewcorp.webwallet.demo;

import com.andrewcorp.webwallet.auth.AuthService;
import com.andrewcorp.webwallet.auth.SessionResponse;
import com.andrewcorp.webwallet.auth.dto.LoginResponse;
import com.andrewcorp.webwallet.common.web.ClientInfo;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The demo sandbox: open one without registration, keep it with an email and a password. */
@Tag(name = "Demo")
@RestController
@RequestMapping("/api/v1")
public class DemoController {

    private final DemoService demo;
    private final SessionResponse sessionResponse;

    public DemoController(DemoService demo, SessionResponse sessionResponse) {
        this.demo = demo;
        this.sessionResponse = sessionResponse;
    }

    @Operation(
            summary = "Open a demo sandbox and sign in to it",
            description = "Public. Creates an account with example data in the language of Accept-Language and"
                    + " starts a session like /api/v1/auth/login; the account is deleted after a day unless it is"
                    + " claimed at /api/v1/me/demo/claim. Limited per client IP; 404 when the demo is off.")
    @PostMapping("/auth/demo")
    public ResponseEntity<LoginResponse> startDemo(Locale locale, HttpServletRequest httpRequest, ClientInfo client) {
        return sessionResponse.login(
                new AuthService.LoginResult(demo.start(locale, httpRequest.getRemoteAddr(), client.kind()), null),
                client);
    }

    /**
     * @param email the address of the account from now on; a verification email goes there
     * @param password the password from now on
     * @param firstName replaces the demo name
     */
    public record ClaimRequest(
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotBlank @Size(max = 100) String firstName) {}

    @Operation(
            summary = "Keep the demo sandbox as an account",
            description = "The signed-in demo account gets the email and password and stops expiring; its data stays."
                    + " The email is verified through the usual message. Only a demo account can be claimed.")
    @PostMapping("/me/demo/claim")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void claimDemo(@CurrentUserId Long userId, @Valid @RequestBody ClaimRequest request) {
        demo.claim(userId, request.email(), request.password(), request.firstName());
    }
}
