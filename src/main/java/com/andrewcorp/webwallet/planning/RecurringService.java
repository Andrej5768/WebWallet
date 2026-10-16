package com.andrewcorp.webwallet.planning;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.util.Zones;
import com.andrewcorp.webwallet.ledger.NamedResponse;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.account.AccountRepository;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import com.andrewcorp.webwallet.ledger.payee.PayeeRepository;
import com.andrewcorp.webwallet.ledger.payee.PayeeService;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionService;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionRequest;
import com.andrewcorp.webwallet.ledger.transaction.dto.TransactionResponse;
import com.andrewcorp.webwallet.planning.RecurringDtos.ConfirmRequest;
import com.andrewcorp.webwallet.planning.RecurringDtos.OccurrenceResponse;
import com.andrewcorp.webwallet.planning.RecurringDtos.RecurringRequest;
import com.andrewcorp.webwallet.planning.RecurringDtos.RecurringResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recurring payments and their occurrences. A pending occurrence is paid by a transaction in one of four ways: a
 * transaction (usually imported from a bank) is matched to it automatically, the user links an existing one, the user
 * confirms it, or, with {@code autoCreate}, it is created on the due date. Occurrences overdue for more than
 * {@value #LOOKBACK_DAYS} days are no longer matched or shown.
 */
@Service
public class RecurringService {

    /** How long an unpaid occurrence stays overdue before it is dropped. */
    public static final int LOOKBACK_DAYS = 31;

    /** A bank operation may come a few days before or after the scheduled date. */
    private static final int MAX_MATCH_DAYS = 5;

    /** Manual linking offers transactions within this many days and with up to half the amount off. */
    private static final int LINK_DAYS = 10;

    private static final BigDecimal LINK_TOLERANCE = new BigDecimal("0.5");
    private static final int LINK_CANDIDATES = 20;
    private static final int DEFAULT_TOLERANCE = 10;
    private static final int VARIABLE_TOLERANCE = 25;
    private static final int MAX_OCCURRENCES = 1000;

    private final RecurringPaymentRepository recurringPayments;
    private final RecurringOccurrenceRepository occurrences;
    private final PlanningRepository planning;
    private final TransactionRepository transactions;
    private final TransactionService transactionService;
    private final AccountRepository accounts;
    private final CategoryRepository categories;
    private final PayeeRepository payees;
    private final PayeeService payeeService;
    private final Clock clock;

    public RecurringService(
            RecurringPaymentRepository recurringPayments,
            RecurringOccurrenceRepository occurrences,
            PlanningRepository planning,
            TransactionRepository transactions,
            TransactionService transactionService,
            AccountRepository accounts,
            CategoryRepository categories,
            PayeeRepository payees,
            PayeeService payeeService,
            Clock clock) {
        this.recurringPayments = recurringPayments;
        this.occurrences = occurrences;
        this.planning = planning;
        this.transactions = transactions;
        this.transactionService = transactionService;
        this.accounts = accounts;
        this.categories = categories;
        this.payees = payees;
        this.payeeService = payeeService;
        this.clock = clock;
    }

    @Transactional
    public List<RecurringResponse> list(Long userId) {
        refresh(userId);
        LocalDate today = today();
        Map<Key, RecurringOccurrence> stored = stored(userId, today.minusDays(LOOKBACK_DAYS), Transaction.MAX_DATE);
        return recurringPayments.findByUserIdOrderByNameAscIdAsc(userId).stream()
                .map(recurring -> response(recurring, nextDate(recurring, stored, today)))
                .toList();
    }

    @Transactional
    public RecurringResponse get(Long userId, Long id) {
        refresh(userId);
        LocalDate today = today();
        RecurringPayment recurring = find(userId, id);
        Map<Key, RecurringOccurrence> stored = stored(userId, today.minusDays(LOOKBACK_DAYS), Transaction.MAX_DATE);
        return response(recurring, nextDate(recurring, stored, today));
    }

    @Transactional
    public RecurringResponse create(Long userId, RecurringRequest request) {
        RecurringPayment recurring = new RecurringPayment(userId);
        apply(userId, recurring, request);
        recurringPayments.save(recurring);
        return get(userId, recurring.getId());
    }

    @Transactional
    public RecurringResponse update(Long userId, Long id, RecurringRequest request) {
        apply(userId, find(userId, id), request);
        return get(userId, id);
    }

    /** Deletes the recurring payment; the transactions that paid it stay. */
    @Transactional
    public void delete(Long userId, Long id) {
        recurringPayments.delete(find(userId, id));
    }

    /**
     * Occurrences of all recurring payments in the period, by date. Pending ones are left out when they are overdue
     * for more than {@value #LOOKBACK_DAYS} days.
     */
    @Transactional
    public List<OccurrenceResponse> occurrences(Long userId, LocalDate from, LocalDate to) {
        refresh(userId);
        LocalDate today = today();
        LocalDate oldestPending = today.minusDays(LOOKBACK_DAYS);
        Map<Key, RecurringOccurrence> stored = stored(userId, from, to);
        List<OccurrenceResponse> result = new ArrayList<>();
        for (RecurringPayment recurring : recurringPayments.findByUserIdOrderByNameAscIdAsc(userId)) {
            for (LocalDate date : dates(recurring, from, to)) {
                RecurringOccurrence occurrence = stored.get(new Key(recurring.getId(), date));
                OccurrenceStatus status = occurrence != null ? occurrence.getStatus() : OccurrenceStatus.PENDING;
                if (status == OccurrenceStatus.PENDING && date.isBefore(oldestPending)) {
                    continue;
                }
                result.add(occurrenceResponse(recurring, date, occurrence, today));
            }
        }
        result.sort(Comparator.comparing(OccurrenceResponse::date).thenComparing(OccurrenceResponse::name));
        return result;
    }

    /** Pending occurrences from today to the date, overdue ones included, for the forecast. */
    @Transactional
    public List<OccurrenceResponse> pending(Long userId, LocalDate to) {
        LocalDate today = today();
        return occurrences(userId, today.minusDays(LOOKBACK_DAYS), to).stream()
                .filter(occurrence -> occurrence.status() == OccurrenceStatus.PENDING)
                .toList();
    }

    /** Creates the transaction of the occurrence with the values of the recurring payment and the given ones. */
    @Transactional
    public OccurrenceResponse confirm(Long userId, Long id, LocalDate date, ConfirmRequest request) {
        RecurringPayment recurring = find(userId, id);
        RecurringOccurrence occurrence = occurrence(recurring, date);
        if (occurrence.getStatus() == OccurrenceStatus.PAID) {
            throw ApiException.invalid("date", "error.recurring.paid");
        }
        LocalDate today = today();
        LocalDate transactionDate = request.date() != null ? request.date() : date.isAfter(today) ? today : date;
        Transaction transaction = createTransaction(recurring, transactionDate, request.amount(), request.toAmount());
        occurrence.paid(transaction);
        occurrences.save(occurrence);
        return occurrenceResponse(recurring, date, occurrence, today);
    }

    /** Marks the occurrence as paid by an existing transaction of the user. */
    @Transactional
    public OccurrenceResponse link(Long userId, Long id, LocalDate date, Long transactionId) {
        RecurringPayment recurring = find(userId, id);
        RecurringOccurrence occurrence = occurrence(recurring, date);
        Transaction transaction = transactions
                .findByIdAndUserId(transactionId, userId)
                .orElseThrow(() -> ApiException.invalid("transactionId", "error.notFound"));
        if (!occurrences.findByTransactionIds(List.of(transactionId)).stream()
                .allMatch(other -> other.getId().equals(occurrence.getId()))) {
            throw ApiException.invalid("transactionId", "error.recurring.transactionLinked");
        }
        occurrence.paid(transaction);
        occurrences.save(occurrence);
        return occurrenceResponse(recurring, date, occurrence, today());
    }

    @Transactional
    public OccurrenceResponse skip(Long userId, Long id, LocalDate date) {
        RecurringPayment recurring = find(userId, id);
        RecurringOccurrence occurrence = occurrence(recurring, date);
        occurrence.skipped();
        occurrences.save(occurrence);
        return occurrenceResponse(recurring, date, occurrence, today());
    }

    /**
     * Makes the occurrence pending again: unlinks its transaction (which stays) or undoes the skip. It is then no
     * longer matched or created automatically.
     */
    @Transactional
    public OccurrenceResponse reset(Long userId, Long id, LocalDate date) {
        RecurringPayment recurring = find(userId, id);
        RecurringOccurrence occurrence = occurrence(recurring, date);
        occurrence.pending();
        occurrences.save(occurrence);
        return occurrenceResponse(recurring, date, occurrence, today());
    }

    /**
     * Transactions that could pay the occurrence, best first: of the same type around the date, with a similar
     * amount, not paying another occurrence. Those on the account of the recurring payment come first.
     */
    @Transactional(readOnly = true)
    public List<TransactionResponse> candidates(Long userId, Long id, LocalDate date) {
        RecurringPayment recurring = find(userId, id);
        occurrenceDate(recurring, date);
        Set<Long> linked = occurrences.findLinkedTransactionIds(userId);
        BigDecimal delta = recurring.getAmount().multiply(LINK_TOLERANCE);
        List<Transaction> candidates = planning.findAnyCandidates(
                userId,
                recurring.getType(),
                recurring.getAmount().subtract(delta),
                recurring.getAmount().add(delta),
                date.minusDays(LINK_DAYS),
                date.plusDays(LINK_DAYS));
        return candidates.stream()
                .filter(candidate -> !linked.contains(candidate.getId()))
                .sorted(Comparator.comparing((Transaction candidate) -> !candidate
                                .getAccount()
                                .getId()
                                .equals(recurring.getAccount().getId()))
                        .thenComparing(score(recurring, date)))
                .limit(LINK_CANDIDATES)
                .map(transactionService::response)
                .toList();
    }

    /**
     * Matches the due occurrences of the user's recurring payments with transactions and creates those of
     * {@code autoCreate} payments. Called before the occurrences are read and regularly by {@link RecurringTask}.
     */
    @Transactional
    public void refresh(Long userId) {
        LocalDate today = today();
        LocalDate from = today.minusDays(LOOKBACK_DAYS);
        Set<Long> linked = occurrences.findLinkedTransactionIds(userId);
        Map<Key, RecurringOccurrence> stored = stored(userId, from, today.plusDays(MAX_MATCH_DAYS));
        for (RecurringPayment recurring : recurringPayments.findByUserIdOrderByNameAscIdAsc(userId)) {
            int window = matchDays(recurring);
            LocalDate created = recurring.getCreatedAt().atZone(Zones.KYIV).toLocalDate();
            for (LocalDate date : dates(recurring, from, today.plusDays(window))) {
                if (stored.containsKey(new Key(recurring.getId(), date))) {
                    continue;
                }
                Transaction match = match(recurring, date, window, linked);
                if (match == null
                        && recurring.isAutoCreate()
                        && !date.isAfter(today)
                        && !date.isBefore(created)
                        && creatable(recurring)) {
                    match = createTransaction(recurring, date, null, null);
                }
                if (match != null) {
                    RecurringOccurrence occurrence = new RecurringOccurrence(recurring, date);
                    occurrence.paid(match);
                    occurrences.save(occurrence);
                    linked.add(match.getId());
                }
            }
        }
    }

    /**
     * Whether the transaction can be created as the payment is: the kind of its category may have changed since. A
     * failed creation would roll back the matching of all occurrences of the user.
     */
    private static boolean creatable(RecurringPayment recurring) {
        Category category = recurring.getCategory();
        if (category == null || recurring.getType() == TransactionType.TRANSFER) {
            return true;
        }
        CategoryKind kind = CategoryKind.of(recurring.getType());
        return category.getKind() == kind
                || (kind == CategoryKind.INCOME && category.getKind() == CategoryKind.EXPENSE);
    }

    /** The transaction that pays the occurrence, or null: within the tolerance and the days, best first. */
    private Transaction match(RecurringPayment recurring, LocalDate date, int window, Set<Long> linked) {
        BigDecimal delta = recurring
                .getAmount()
                .multiply(BigDecimal.valueOf(recurring.getTolerance()))
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
        BigDecimal min = recurring.getAmount().subtract(delta);
        BigDecimal max = recurring.getAmount().add(delta);
        List<Transaction> candidates = recurring.getType() == TransactionType.TRANSFER
                ? planning.findTransferCandidates(
                        recurring.getUserId(),
                        recurring.getAccount(),
                        recurring.getToAccount(),
                        min,
                        max,
                        date.minusDays(window),
                        date.plusDays(window))
                : planning.findCandidates(
                        recurring.getUserId(),
                        recurring.getAccount(),
                        recurring.getType(),
                        min,
                        max,
                        date.minusDays(window),
                        date.plusDays(window));
        return candidates.stream()
                .filter(candidate -> !linked.contains(candidate.getId()))
                .filter(candidate -> !unrelated(recurring, candidate))
                .min(score(recurring, date))
                .orElse(null);
    }

    /** Another payee and another category: a different payment of a similar amount. */
    private static boolean unrelated(RecurringPayment recurring, Transaction candidate) {
        boolean otherPayee = recurring.getPayee() != null
                && candidate.getPayee() != null
                && !recurring.getPayee().getId().equals(candidate.getPayee().getId());
        Long category =
                recurring.getCategory() != null ? recurring.getCategory().getId() : null;
        boolean otherCategory = category != null
                && candidate.getSplits().stream()
                        .allMatch(split -> split.getCategory() != null
                                && !split.getCategory().getId().equals(category));
        return otherPayee && otherCategory;
    }

    /** The same payee first, then the closest amount, then the closest date. */
    private static Comparator<Transaction> score(RecurringPayment recurring, LocalDate date) {
        return Comparator.comparing((Transaction candidate) -> recurring.getPayee() == null
                        || candidate.getPayee() == null
                        || !recurring
                                .getPayee()
                                .getId()
                                .equals(candidate.getPayee().getId()))
                .thenComparing(candidate ->
                        candidate.getAmount().subtract(recurring.getAmount()).abs())
                .thenComparing(candidate -> Math.abs(ChronoUnit.DAYS.between(date, candidate.getDate())))
                .thenComparing(Transaction::getId);
    }

    /** Days a matching transaction may be away from the scheduled date: up to 5, less for frequent payments. */
    private static int matchDays(RecurringPayment recurring) {
        return Math.min(MAX_MATCH_DAYS, (recurring.getFrequency().approximateDays(recurring.getInterval()) - 1) / 2);
    }

    private Transaction createTransaction(
            RecurringPayment recurring, LocalDate date, BigDecimal amount, BigDecimal toAmount) {
        boolean transfer = recurring.getType() == TransactionType.TRANSFER;
        TransactionRequest request = new TransactionRequest(
                recurring.getType(),
                date,
                recurring.getAccount().getId(),
                amount != null ? amount : recurring.getAmount(),
                transfer ? recurring.getToAccount().getId() : null,
                transfer ? (toAmount != null ? toAmount : recurring.getToAmount()) : null,
                null,
                null,
                !transfer && recurring.getCategory() != null
                        ? recurring.getCategory().getId()
                        : null,
                null,
                recurring.getPayee() != null ? recurring.getPayee().getId() : null,
                null,
                recurring.getComment(),
                null);
        Long id = transactionService.create(recurring.getUserId(), request).id();
        return transactions.getReferenceById(id);
    }

    /** Scheduled dates of the recurring payment in the period. */
    static List<LocalDate> dates(RecurringPayment recurring, LocalDate from, LocalDate to) {
        List<LocalDate> dates = new ArrayList<>();
        for (long index = firstIndex(recurring, from); dates.size() < MAX_OCCURRENCES; index++) {
            LocalDate date = recurring.occurrence(index);
            if (date == null || date.isAfter(to)) {
                break;
            }
            if (!date.isBefore(from)) {
                dates.add(date);
            }
        }
        return dates;
    }

    /** An index of the schedule at or a little before the date. */
    private static long firstIndex(RecurringPayment recurring, LocalDate date) {
        if (!date.isAfter(recurring.getStartDate())) {
            return 0;
        }
        long index = ChronoUnit.DAYS.between(recurring.getStartDate(), date)
                / recurring.getFrequency().approximateDays(recurring.getInterval());
        // The estimate may overshoot by a little, e.g. 30 days for a month: start a step earlier
        return Math.max(0, index - 1);
    }

    /** The earliest occurrence that is neither paid nor skipped, from the oldest overdue one on. */
    private static LocalDate nextDate(
            RecurringPayment recurring, Map<Key, RecurringOccurrence> stored, LocalDate today) {
        LocalDate from = today.minusDays(LOOKBACK_DAYS);
        long index = firstIndex(recurring, from);
        for (int i = 0; i < MAX_OCCURRENCES; i++, index++) {
            LocalDate date = recurring.occurrence(index);
            if (date == null) {
                return null;
            }
            if (date.isBefore(from)) {
                continue;
            }
            RecurringOccurrence occurrence = stored.get(new Key(recurring.getId(), date));
            if (occurrence == null || occurrence.getStatus() == OccurrenceStatus.PENDING) {
                return date;
            }
        }
        return null;
    }

    private Map<Key, RecurringOccurrence> stored(Long userId, LocalDate from, LocalDate to) {
        Map<Key, RecurringOccurrence> stored = new HashMap<>();
        for (RecurringOccurrence occurrence : occurrences.findByUser(userId, from, to)) {
            stored.put(new Key(occurrence.getRecurring().getId(), occurrence.getDueDate()), occurrence);
        }
        return stored;
    }

    /** The stored occurrence of the scheduled date, or a new one. */
    private RecurringOccurrence occurrence(RecurringPayment recurring, LocalDate date) {
        occurrenceDate(recurring, date);
        return occurrences
                .findByRecurringAndDueDate(recurring, date)
                .orElseGet(() -> new RecurringOccurrence(recurring, date));
    }

    /** Rejects a date that is not on the schedule. */
    private static void occurrenceDate(RecurringPayment recurring, LocalDate date) {
        if (!dates(recurring, date, date).contains(date)) {
            throw ApiException.notFound();
        }
    }

    private RecurringPayment find(Long userId, Long id) {
        return recurringPayments.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound);
    }

    private void apply(Long userId, RecurringPayment recurring, RecurringRequest request) {
        if (request.startDate().isBefore(Transaction.MIN_DATE)
                || request.startDate().isAfter(Transaction.MAX_DATE)) {
            throw ApiException.invalid("startDate", "error.transaction.date");
        }
        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw ApiException.invalid("endDate", "error.recurring.endDate");
        }
        Account account = account(userId, request.accountId(), "accountId");
        recurring.setName(request.name().trim());
        recurring.setType(request.type());
        recurring.setAccount(account);
        recurring.setAmount(request.amount());
        if (request.type() == TransactionType.TRANSFER) {
            if (request.toAccountId() == null) {
                throw ApiException.invalid("toAccountId", "error.required");
            }
            if (Objects.equals(request.toAccountId(), request.accountId())) {
                throw ApiException.invalid("toAccountId", "error.transaction.sameAccount");
            }
            if (request.categoryId() != null) {
                throw ApiException.invalid("categoryId", "error.transaction.transferCategory");
            }
            Account toAccount = account(userId, request.toAccountId(), "toAccountId");
            if (request.toAmount() == null && !toAccount.getCurrency().equals(account.getCurrency())) {
                throw ApiException.invalid("toAmount", "error.required");
            }
            recurring.setToAccount(toAccount);
            recurring.setToAmount(request.toAmount() != null ? request.toAmount() : request.amount());
            recurring.setCategory(null);
        } else {
            if (request.toAccountId() != null || request.toAmount() != null) {
                throw ApiException.invalid("toAccountId", "error.transaction.transferOnly");
            }
            recurring.setToAccount(null);
            recurring.setToAmount(null);
            recurring.setCategory(category(userId, request.categoryId(), CategoryKind.of(request.type())));
        }
        recurring.setPayee(payee(userId, request));
        recurring.setComment(request.comment());
        recurring.setFrequency(request.frequency());
        recurring.setInterval(request.interval() != null ? request.interval() : 1);
        recurring.setStartDate(request.startDate());
        recurring.setEndDate(request.endDate());
        recurring.setAutoCreate(Boolean.TRUE.equals(request.autoCreate()));
        recurring.setVariableAmount(Boolean.TRUE.equals(request.variableAmount()));
        int defaultTolerance = recurring.isVariableAmount() ? VARIABLE_TOLERANCE : DEFAULT_TOLERANCE;
        recurring.setTolerance(request.tolerance() != null ? request.tolerance() : defaultTolerance);
    }

    private Account account(Long userId, Long id, String field) {
        return accounts.findByIdAndUserId(id, userId).orElseThrow(() -> ApiException.invalid(field, "error.notFound"));
    }

    /** An income may have an expense category, like a refund. */
    private Category category(Long userId, Long id, CategoryKind kind) {
        if (id == null) {
            return null;
        }
        Category category = categories
                .findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.invalid("categoryId", "error.notFound"));
        boolean refund = kind == CategoryKind.INCOME && category.getKind() == CategoryKind.EXPENSE;
        if (category.getKind() != kind && !refund) {
            throw ApiException.invalid("categoryId", "error.category.kind");
        }
        return category;
    }

    private Payee payee(Long userId, RecurringRequest request) {
        if (request.payeeId() != null) {
            return payees.findByIdAndUserId(request.payeeId(), userId)
                    .orElseThrow(() -> ApiException.invalid("payeeId", "error.notFound"));
        }
        if (request.payeeName() != null && !request.payeeName().isBlank()) {
            return payeeService.findOrCreate(userId, request.payeeName());
        }
        return null;
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static RecurringResponse response(RecurringPayment recurring, LocalDate nextDate) {
        boolean transfer = recurring.getToAccount() != null;
        return new RecurringResponse(
                recurring.getId(),
                recurring.getName(),
                recurring.getType(),
                recurring.getAccount().getId(),
                recurring.getAmount(),
                recurring.getAccount().getCurrency(),
                transfer ? recurring.getToAccount().getId() : null,
                recurring.getToAmount(),
                transfer ? recurring.getToAccount().getCurrency() : null,
                recurring.getCategory() != null ? recurring.getCategory().getId() : null,
                payee(recurring),
                recurring.getComment(),
                recurring.getFrequency(),
                recurring.getInterval(),
                recurring.getStartDate(),
                recurring.getEndDate(),
                recurring.isAutoCreate(),
                recurring.isVariableAmount(),
                recurring.getTolerance(),
                nextDate);
    }

    private static OccurrenceResponse occurrenceResponse(
            RecurringPayment recurring, LocalDate date, RecurringOccurrence occurrence, LocalDate today) {
        OccurrenceStatus status = occurrence != null ? occurrence.getStatus() : OccurrenceStatus.PENDING;
        boolean transfer = recurring.getToAccount() != null;
        return new OccurrenceResponse(
                recurring.getId(),
                recurring.getName(),
                date,
                status,
                status == OccurrenceStatus.PENDING && date.isBefore(today),
                recurring.getType(),
                recurring.getAccount().getId(),
                recurring.getAmount(),
                recurring.getAccount().getCurrency(),
                transfer ? recurring.getToAccount().getId() : null,
                recurring.getToAmount(),
                recurring.getCategory() != null ? recurring.getCategory().getId() : null,
                payee(recurring),
                recurring.isAutoCreate(),
                recurring.isVariableAmount(),
                occurrence != null && occurrence.getTransaction() != null
                        ? occurrence.getTransaction().getId()
                        : null);
    }

    private static NamedResponse payee(RecurringPayment recurring) {
        return recurring.getPayee() != null
                ? new NamedResponse(
                        recurring.getPayee().getId(), recurring.getPayee().getName())
                : null;
    }

    private record Key(Long recurringId, LocalDate date) {}
}
