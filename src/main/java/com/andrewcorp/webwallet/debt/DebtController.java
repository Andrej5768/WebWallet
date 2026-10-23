package com.andrewcorp.webwallet.debt;

import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.debt.DebtDtos.DebtsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Debts with people: who owes whom, from the transfers with the debt accounts. */
@Tag(name = "Debts")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/debts")
public class DebtController {

    private final DebtService debtService;

    public DebtController(DebtService debtService) {
        this.debtService = debtService;
    }

    @Operation(
            summary = "Debts with people",
            description = "Everyone with a debt operation and their balance in the base currency: a transfer to a debt"
                    + " account is money given to its payee, a transfer from one is money received. Positive means"
                    + " the person owes the user. Operations in a currency without a rate are left out and the currency"
                    + " is listed in unconvertedCurrencies.")
    @GetMapping
    public DebtsResponse debts(@CurrentUserId Long userId) {
        return debtService.debts(userId);
    }
}
