package com.andrewcorp.webwallet.user;

import com.andrewcorp.webwallet.auth.twofactor.TwoFactorService;
import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.user.dto.DisableTwoFactorRequest;
import com.andrewcorp.webwallet.user.dto.RecoveryCodesResponse;
import com.andrewcorp.webwallet.user.dto.TwoFactorCodeRequest;
import com.andrewcorp.webwallet.user.dto.TwoFactorSetupResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Current user")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/me/two-factor")
public class TwoFactorController {

    private final TwoFactorService twoFactor;

    public TwoFactorController(TwoFactorService twoFactor) {
        this.twoFactor = twoFactor;
    }

    @Operation(
            summary = "Start turning on two-factor authentication",
            description = "Returns a new secret for the authenticator app; it becomes active once /enable gets a code"
                    + " of it. Fails with CONFLICT when two-factor authentication is already on.")
    @PostMapping("/setup")
    public TwoFactorSetupResponse setup(@CurrentUserId Long userId) {
        TwoFactorService.Setup setup = twoFactor.setup(userId);
        return new TwoFactorSetupResponse(setup.secret(), setup.otpauthUri());
    }

    @Operation(
            summary = "Turn on two-factor authentication with a code of the new secret",
            description = "Returns the recovery codes; they are not shown again. Trusted browsers are forgotten.")
    @PostMapping("/enable")
    public RecoveryCodesResponse enable(@CurrentUserId Long userId, @Valid @RequestBody TwoFactorCodeRequest request) {
        return new RecoveryCodesResponse(twoFactor.enable(userId, request.code()));
    }

    @Operation(
            summary = "Turn off two-factor authentication",
            description = "Needs the password (unless the account has none) and a code of the app or a recovery code."
                    + " Recovery codes and trusted browsers are deleted.")
    @PostMapping("/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void disable(@CurrentUserId Long userId, @Valid @RequestBody DisableTwoFactorRequest request) {
        twoFactor.disable(userId, request.password(), request.code());
    }

    @Operation(
            summary = "Replace the recovery codes",
            description = "Needs a code of the authenticator app; the old recovery codes stop working")
    @PostMapping("/recovery-codes")
    public RecoveryCodesResponse regenerateRecoveryCodes(
            @CurrentUserId Long userId, @Valid @RequestBody TwoFactorCodeRequest request) {
        return new RecoveryCodesResponse(twoFactor.regenerateRecoveryCodes(userId, request.code()));
    }
}
