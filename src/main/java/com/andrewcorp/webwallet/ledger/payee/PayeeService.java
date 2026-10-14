package com.andrewcorp.webwallet.ledger.payee;

import com.andrewcorp.webwallet.common.error.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class PayeeService {

    private final PayeeRepository payees;

    public PayeeService(PayeeRepository payees) {
        this.payees = payees;
    }

    @Transactional(readOnly = true)
    public List<Payee> list(Long userId) {
        return payees.findByUserIdOrderByNameAsc(userId);
    }

    @Transactional(readOnly = true)
    public Payee find(Long userId, Long id) {
        return payees.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound);
    }

    /** Returns the payee with this name (case-insensitive) or creates it. */
    @Transactional
    public Payee findOrCreate(Long userId, String name) {
        String trimmed = name.trim();
        return payees.findByUserIdAndNameIgnoreCase(userId, trimmed)
                .orElseGet(() -> payees.save(new Payee(userId, trimmed)));
    }

    @Transactional
    public Payee create(Long userId, String name) {
        String trimmed = name.trim();
        if (payees.findByUserIdAndNameIgnoreCase(userId, trimmed).isPresent()) {
            throw ApiException.invalid("name", "error.duplicateName");
        }
        return payees.save(new Payee(userId, trimmed));
    }

    @Transactional
    public Payee rename(Long userId, Long id, String name) {
        Payee payee = find(userId, id);
        String trimmed = name.trim();
        payees.findByUserIdAndNameIgnoreCase(userId, trimmed)
                .filter(other -> !other.getId().equals(payee.getId()))
                .ifPresent(other -> {
                    throw ApiException.invalid("name", "error.duplicateName");
                });
        payee.setName(trimmed);
        return payee;
    }

    /** Transactions of a deleted payee keep existing without a payee. */
    @Transactional
    public void delete(Long userId, Long id) {
        payees.delete(find(userId, id));
    }
}
