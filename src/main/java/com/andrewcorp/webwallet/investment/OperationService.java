package com.andrewcorp.webwallet.investment;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.investment.InvestmentDtos.OperationRequest;
import com.andrewcorp.webwallet.investment.InvestmentDtos.OperationResponse;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.account.AccountType;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Trades, income and fees of brokerage accounts. A change that would sell more than is held is rejected. */
@Service
public class OperationService {

    private final InvestmentOperationRepository operations;
    private final AccountRepository accounts;
    private final InstrumentService instrumentService;

    public OperationService(
            InvestmentOperationRepository operations, AccountRepository accounts, InstrumentService instrumentService) {
        this.operations = operations;
        this.accounts = accounts;
        this.instrumentService = instrumentService;
    }

    @Transactional(readOnly = true)
    public Page<OperationResponse> list(Long userId, Long accountId, Long instrumentId, Pageable pageable) {
        return operations.search(userId, accountId, instrumentId, pageable).map(OperationResponse::from);
    }

    @Transactional(readOnly = true)
    public OperationResponse get(Long userId, Long id) {
        return OperationResponse.from(find(userId, id));
    }

    @Transactional
    public OperationResponse create(Long userId, OperationRequest request) {
        InvestmentOperation operation = new InvestmentOperation(userId);
        apply(userId, operation, request);
        operations.save(operation);
        checkLots(operation.getAccount(), operation.getInstrument());
        return OperationResponse.from(operation);
    }

    @Transactional
    public OperationResponse update(Long userId, Long id, OperationRequest request) {
        InvestmentOperation operation = find(userId, id);
        Account accountBefore = operation.getAccount();
        Instrument instrumentBefore = operation.getInstrument();
        apply(userId, operation, request);
        operations.flush();
        checkLots(operation.getAccount(), operation.getInstrument());
        checkLots(accountBefore, instrumentBefore);
        return OperationResponse.from(operation);
    }

    /** Deleting a purchase whose units were sold later is rejected. */
    @Transactional
    public void delete(Long userId, Long id) {
        InvestmentOperation operation = find(userId, id);
        operations.delete(operation);
        operations.flush();
        checkLots(operation.getAccount(), operation.getInstrument());
    }

    private InvestmentOperation find(Long userId, Long id) {
        return operations.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound);
    }

    /** Replays the lots of the instrument on the account: every sale must find the units it sells. */
    private void checkLots(Account account, Instrument instrument) {
        if (instrument == null) {
            return;
        }
        try {
            LotLedger.of(operations.findByAccountAndInstrument(account, instrument));
        } catch (LotLedger.Shortfall e) {
            throw ApiException.invalid("quantity", "error.investment.shortfall");
        }
    }

    private void apply(Long userId, InvestmentOperation operation, OperationRequest request) {
        if (request.date().isBefore(Transaction.MIN_DATE) || request.date().isAfter(Transaction.MAX_DATE)) {
            throw ApiException.invalid("date", "error.transaction.date");
        }
        Account account = accounts.findByIdAndUserId(request.accountId(), userId)
                .orElseThrow(() -> ApiException.invalid("accountId", "error.notFound"));
        if (account.getType() != AccountType.INVESTMENT) {
            throw ApiException.invalid("accountId", "error.investment.account");
        }
        OperationType type = request.type();
        Instrument instrument = null;
        if (request.instrumentId() != null) {
            try {
                instrument = instrumentService.find(userId, request.instrumentId());
            } catch (ApiException e) {
                throw ApiException.invalid("instrumentId", "error.notFound");
            }
        } else if (type != OperationType.FEE) {
            throw ApiException.invalid("instrumentId", "error.required");
        }

        BigDecimal quantity = null;
        BigDecimal price = null;
        BigDecimal amount;
        switch (type) {
            case BUY, SELL -> {
                quantity = required(request.quantity(), "quantity");
                price = required(request.price(), "price");
                amount = request.amount() != null
                        ? request.amount()
                        : quantity.multiply(price).setScale(4, RoundingMode.HALF_UP);
            }
            case DIVIDEND, COUPON, FEE -> amount = required(request.amount(), "amount");
            case SPLIT -> {
                required(request.splitFrom(), "splitFrom");
                required(request.splitTo(), "splitTo");
                amount = BigDecimal.ZERO;
            }
            default -> throw new IllegalStateException(type.name());
        }
        BigDecimal tax = type == OperationType.DIVIDEND || type == OperationType.COUPON ? orZero(request.tax()) : null;
        if (tax != null && tax.compareTo(amount) > 0) {
            throw ApiException.invalid("tax", "error.investment.tax");
        }
        BigDecimal fee = type == OperationType.BUY || type == OperationType.SELL ? orZero(request.fee()) : null;

        operation.setType(type);
        operation.setAccount(account);
        operation.setInstrument(instrument);
        operation.setDate(request.date());
        operation.setQuantity(quantity);
        operation.setPrice(price);
        operation.setAmount(amount);
        operation.setFee(fee != null ? fee : BigDecimal.ZERO);
        operation.setTax(tax != null ? tax : BigDecimal.ZERO);
        operation.setCurrency(
                request.currency() != null
                        ? request.currency()
                        : instrument != null ? instrument.getCurrency() : account.getCurrency());
        operation.setSplitFrom(type == OperationType.SPLIT ? request.splitFrom() : null);
        operation.setSplitTo(type == OperationType.SPLIT ? request.splitTo() : null);
        operation.setComment(request.comment());
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw ApiException.invalid(field, "error.required");
        }
        return value;
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
