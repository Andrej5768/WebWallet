package com.andrewcorp.webwallet.ledger.payee;

import com.andrewcorp.webwallet.common.web.CurrentUserId;
import com.andrewcorp.webwallet.ledger.NameRequest;
import com.andrewcorp.webwallet.ledger.NamedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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

@Tag(name = "Payees")
@SecurityRequirement(name = "bearer")
@RestController
@RequestMapping("/api/v1/payees")
public class PayeeController {

    private final PayeeService payeeService;

    public PayeeController(PayeeService payeeService) {
        this.payeeService = payeeService;
    }

    @GetMapping
    public List<NamedResponse> list(@CurrentUserId Long userId) {
        return payeeService.list(userId).stream()
                .map(PayeeController::toResponse)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NamedResponse create(@CurrentUserId Long userId, @Valid @RequestBody NameRequest request) {
        return toResponse(payeeService.create(userId, request.name()));
    }

    @PutMapping("/{id}")
    public NamedResponse rename(
            @CurrentUserId Long userId, @PathVariable Long id, @Valid @RequestBody NameRequest request) {
        return toResponse(payeeService.rename(userId, id, request.name()));
    }

    @Operation(summary = "Delete a payee; its transactions stay without a payee")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@CurrentUserId Long userId, @PathVariable Long id) {
        payeeService.delete(userId, id);
    }

    private static NamedResponse toResponse(Payee payee) {
        return new NamedResponse(payee.getId(), payee.getName());
    }
}
