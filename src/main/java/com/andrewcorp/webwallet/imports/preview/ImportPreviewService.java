package com.andrewcorp.webwallet.imports.preview;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.common.util.Lists;
import com.andrewcorp.webwallet.common.util.Texts;
import com.andrewcorp.webwallet.common.web.PageResponse;
import com.andrewcorp.webwallet.imports.ImportOperation;
import com.andrewcorp.webwallet.imports.ImportPreviewCollector;
import com.andrewcorp.webwallet.imports.ImportResult;
import com.andrewcorp.webwallet.imports.ImportReview;
import com.andrewcorp.webwallet.imports.preview.ImportPreviewDtos.ConfirmRequest;
import com.andrewcorp.webwallet.imports.preview.ImportPreviewDtos.ImportPreviewResponse;
import com.andrewcorp.webwallet.imports.preview.ImportPreviewDtos.OperationEdit;
import com.andrewcorp.webwallet.imports.privatbank.PrivatBankImporter;
import com.andrewcorp.webwallet.imports.privatbank.PrivatStatement;
import com.andrewcorp.webwallet.imports.zenmoney.ZenMoneyDump;
import com.andrewcorp.webwallet.imports.zenmoney.ZenMoneyImporter;
import com.andrewcorp.webwallet.ledger.LedgerLookups;
import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.payee.PayeeService;
import com.andrewcorp.webwallet.ledger.rule.RuleService;
import com.andrewcorp.webwallet.ledger.tag.Tag;
import com.andrewcorp.webwallet.ledger.tag.TagService;
import com.andrewcorp.webwallet.ledger.transaction.Transaction;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionType;
import com.andrewcorp.webwallet.ledger.transaction.TransferPairs;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Previews of imports: the import runs in a transaction that is rolled back, which shows exactly what it would do
 * (accounts and categories created, duplicates recognized, categories suggested by the rules) without saving
 * anything. The parsed input is kept for an hour; the confirmation imports it with the changes of the user through
 * the same importer.
 */
@Service
public class ImportPreviewService {

    static final Duration TTL = Duration.ofHours(1);
    /** Older previews of the user are removed when a new one is made. */
    static final int MAX_PREVIEWS_PER_USER = 3;

    public static final int DEFAULT_PAGE_SIZE = 100;
    public static final int MAX_PAGE_SIZE = 500;

    private final ImportPreviewRepository previews;
    private final PrivatBankImporter privatBank;
    private final ZenMoneyImporter zenMoney;
    private final LedgerLookups lookups;
    private final CategoryRepository categories;
    private final TagService tags;
    private final PayeeService payees;
    private final RuleService rules;
    private final JsonMapper jsonMapper;
    private final TransactionRepository transactions;
    private final TransferPairs transferPairs;
    private final TransferSuggestions transferSuggestions;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ImportPreviewService(
            ImportPreviewRepository previews,
            PrivatBankImporter privatBank,
            ZenMoneyImporter zenMoney,
            LedgerLookups lookups,
            CategoryRepository categories,
            TagService tags,
            PayeeService payees,
            RuleService rules,
            JsonMapper jsonMapper,
            TransactionRepository transactions,
            TransferPairs transferPairs,
            TransferSuggestions transferSuggestions,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.previews = previews;
        this.privatBank = privatBank;
        this.zenMoney = zenMoney;
        this.lookups = lookups;
        this.categories = categories;
        this.tags = tags;
        this.payees = payees;
        this.rules = rules;
        this.jsonMapper = jsonMapper;
        this.transactions = transactions;
        this.transferPairs = transferPairs;
        this.transferSuggestions = transferSuggestions;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** The parsed input of a PrivatBank preview. */
    record PrivatInput(Long accountId, PrivatStatement statement) {}

    /** What the preview shows, stored with it. */
    record StoredPreview(ImportResult summary, List<ImportOperation> operations) {}

    public ImportPreviewResponse previewPrivatBank(Long userId, Long accountId, PrivatStatement statement) {
        return create(
                userId,
                ImportSource.PRIVATBANK,
                new PrivatInput(accountId, statement),
                collector -> privatBank.importStatement(userId, accountId, statement, ImportReview.NONE, collector));
    }

    public ImportPreviewResponse previewZenMoney(Long userId, ZenMoneyDump dump) {
        return create(
                userId,
                ImportSource.ZENMONEY,
                dump,
                collector -> zenMoney.importDump(userId, dump, ImportReview.NONE, collector));
    }

    private ImportPreviewResponse create(
            Long userId, ImportSource source, Object input, Function<ImportPreviewCollector, ImportResult> dryRun) {
        ImportPreviewCollector collector = new ImportPreviewCollector();
        ImportResult summary = tx.execute(status -> {
            status.setRollbackOnly();
            return dryRun.apply(collector);
        });
        // Newest first; operations of the same day keep the reverse order of the import
        List<ImportOperation> operations =
                new ArrayList<>(collector.operations().reversed());
        operations.sort(Comparator.comparing(ImportOperation::date).reversed());
        // After the dry run is rolled back: the ledger is read as it is before the import
        operations = transferSuggestions.add(userId, operations);
        StoredPreview stored = new StoredPreview(summary, operations);
        ImportPreview preview = store(userId, source, input, stored);
        return response(preview, stored, 0, DEFAULT_PAGE_SIZE);
    }

    /**
     * Keeps a parsed import for an hour, as JSON: the input to import on confirmation and what the preview shows. The
     * oldest previews of the user go when there are too many.
     */
    public ImportPreview store(Long userId, ImportSource source, Object input, Object shown) {
        Instant now = clock.instant();
        ImportPreview preview = new ImportPreview(
                userId,
                source,
                jsonMapper.writeValueAsString(input),
                jsonMapper.writeValueAsString(shown),
                now,
                now.plus(TTL));
        tx.executeWithoutResult(status -> {
            List<UUID> older = previews.findIdsNewestFirst(userId);
            if (older.size() >= MAX_PREVIEWS_PER_USER) {
                previews.deleteAllByIdInBatch(older.subList(MAX_PREVIEWS_PER_USER - 1, older.size()));
            }
            previews.save(preview);
        });
        return preview;
    }

    /** A preview of the source; 404 when it is unknown, expired or of another source. */
    @Transactional(readOnly = true)
    public ImportPreview find(Long userId, UUID id, ImportSource source) {
        ImportPreview preview = find(userId, id);
        if (preview.getSource() != source) {
            throw ApiException.notFound();
        }
        return preview;
    }

    @Transactional
    public void delete(ImportPreview preview) {
        previews.delete(preview);
    }

    /** A page of the operations of a preview; 404 when it is unknown or expired. */
    @Transactional(readOnly = true)
    public ImportPreviewResponse get(Long userId, UUID id, int page, int size) {
        ImportPreview preview = ledger(userId, id);
        return response(preview, stored(preview), page, size);
    }

    @Transactional
    public void discard(Long userId, UUID id) {
        previews.delete(find(userId, id));
    }

    /**
     * Imports the previewed input with the changes of the user and removes the preview. Rules to remember are
     * created first, so that they also categorize the other operations of the import.
     */
    @Transactional
    public ImportResult confirm(Long userId, UUID id, ConfirmRequest request) {
        ImportPreview preview = ledger(userId, id);
        Map<String, ImportOperation> byKey = new HashMap<>();
        stored(preview).operations().forEach(operation -> byKey.putIfAbsent(operation.key(), operation));

        Map<String, ImportReview.Edit> edits = new HashMap<>();
        List<OperationEdit> requested = Lists.orEmpty(request.operations());
        for (int i = 0; i < requested.size(); i++) {
            OperationEdit edit = requested.get(i);
            String field = "operations[" + i + "]";
            ImportOperation operation = byKey.get(edit.key());
            if (operation == null) {
                throw ApiException.invalid(field + ".key", "error.notFound");
            }
            edits.put(edit.key(), edit(userId, operation, edit, field, byKey));
        }
        checkPairsAreDisjoint(edits);
        for (OperationEdit edit : requested) {
            ImportReview.Edit resolved = edits.get(edit.key());
            String payee = byKey.get(edit.key()).payee();
            if (Boolean.TRUE.equals(edit.rememberRule())
                    && resolved.include()
                    && resolved.category() != null
                    && payee != null) {
                rules.learn(userId, payees.findOrCreate(userId, payee), resolved.category());
            }
        }

        ImportReview review = new ImportReview(edits);
        ImportResult result = switch (preview.getSource()) {
            case PRIVATBANK -> {
                PrivatInput input = jsonMapper.readValue(preview.getInput(), PrivatInput.class);
                yield privatBank.importStatement(userId, input.accountId(), input.statement(), review, null);
            }
            case ZENMONEY ->
                zenMoney.importDump(userId, jsonMapper.readValue(preview.getInput(), ZenMoneyDump.class), review, null);
            case FREEDOM -> throw ApiException.notFound();
        };
        // The chosen pairs become transfers once both halves are saved
        review.pairs().forEach(pair -> transferPairs.merge(userId, pair.expense(), pair.income()));
        previews.delete(preview);
        return result;
    }

    /** Removes the expired previews. */
    @Transactional
    public int purgeExpired() {
        return previews.deleteExpired(clock.instant());
    }

    /** Each operation and transaction is merged with one other at most; a pair chosen on both halves is fine. */
    private static void checkPairsAreDisjoint(Map<String, ImportReview.Edit> edits) {
        Map<String, String> partners = new HashMap<>();
        edits.forEach((key, edit) -> {
            String other = edit.pairKey() != null
                    ? "operation:" + edit.pairKey()
                    : edit.pairTransaction() != null
                            ? "transaction:" + edit.pairTransaction().getId()
                            : null;
            if (edit.include() && other != null) {
                link(partners, "operation:" + key, other);
                link(partners, other, "operation:" + key);
            }
        });
    }

    private static void link(Map<String, String> partners, String half, String other) {
        String existing = partners.putIfAbsent(half, other);
        if (existing != null && !existing.equals(other)) {
            throw ApiException.invalid("operations", "validation.transferPair");
        }
    }

    private ImportReview.Edit edit(
            Long userId,
            ImportOperation operation,
            OperationEdit edit,
            String field,
            Map<String, ImportOperation> byKey) {
        if (Boolean.FALSE.equals(edit.include())) {
            return new ImportReview.Edit(false, null, null, null, false, Set.of(), null);
        }
        TransactionType detected = operation.type();
        TransactionType type = edit.type() != null ? edit.type() : detected;
        if (detected != TransactionType.TRANSFER && type != TransactionType.TRANSFER && type != detected) {
            throw ApiException.invalid(field + ".type", "error.import.direction");
        }
        Account transferAccount = null;
        if (type == TransactionType.TRANSFER && detected != TransactionType.TRANSFER) {
            if (edit.transferAccountId() == null) {
                throw ApiException.invalid(field + ".transferAccountId", "error.required");
            }
            transferAccount = lookups.account(userId, edit.transferAccountId(), field + ".transferAccountId");
            if (Objects.equals(transferAccount.getId(), operation.account().id())) {
                throw ApiException.invalid(field + ".transferAccountId", "error.transaction.sameAccount");
            }
            if (!transferAccount.getCurrency().equals(operation.currency())) {
                throw ApiException.invalid(field + ".transferAccountId", "error.import.transferCurrency");
            }
        }
        boolean keepCategory = Boolean.TRUE.equals(edit.keepSuggestedCategory());
        Category category = null;
        if (keepCategory) {
            // Only for a rule to remember; the import applies the suggestion, also one it creates
            if (operation.categoryId() != null && type != TransactionType.TRANSFER) {
                category = categories
                        .findByIdAndUserId(operation.categoryId(), userId)
                        .orElse(null);
            }
        } else if (edit.categoryId() != null) {
            if (type == TransactionType.TRANSFER) {
                throw ApiException.invalid(field + ".categoryId", "error.transaction.transferCategory");
            }
            category = lookups.category(userId, edit.categoryId(), CategoryKind.of(type), field + ".categoryId");
        }
        Set<Tag> tagSet = tags.findAll(userId, edit.tagIds(), field + ".tagIds");
        String comment = Texts.truncate(Texts.trimToNull(edit.comment()), Transaction.MAX_COMMENT_LENGTH);
        String pairKey = null;
        Transaction pairTransaction = null;
        if (edit.pairKey() != null || edit.pairTransactionId() != null) {
            // Only an expense or an income as detected pairs with the opposite kind of operation
            if (type != detected || detected == TransactionType.TRANSFER) {
                throw ApiException.invalid(field + ".pairKey", "validation.transferPair");
            }
            if (edit.pairKey() != null) {
                ImportOperation other = byKey.get(edit.pairKey());
                if (other == null
                        || other.key().equals(operation.key())
                        || other.duplicate()
                        || other.type() == detected
                        || other.type() == TransactionType.TRANSFER) {
                    throw ApiException.invalid(field + ".pairKey", "validation.transferPair");
                }
                pairKey = edit.pairKey();
            } else {
                pairTransaction = transactions
                        .findByIdAndUserId(edit.pairTransactionId(), userId)
                        .orElseThrow(() -> ApiException.invalid(field + ".pairTransactionId", "error.notFound"));
                if (pairTransaction.getType() == detected || pairTransaction.getType() == TransactionType.TRANSFER) {
                    throw ApiException.invalid(field + ".pairTransactionId", "validation.transferPair");
                }
            }
        }
        return new ImportReview.Edit(
                true, type, transferAccount, category, keepCategory, tagSet, comment, pairKey, pairTransaction);
    }

    /** A preview of ledger transactions; the others have their own pages and confirmation. */
    private ImportPreview ledger(Long userId, UUID id) {
        ImportPreview preview = find(userId, id);
        if (!preview.getSource().ledger()) {
            throw ApiException.notFound();
        }
        return preview;
    }

    private ImportPreview find(Long userId, UUID id) {
        return previews.findByIdAndUserIdAndExpiresAtAfter(id, userId, clock.instant())
                .orElseThrow(ApiException::notFound);
    }

    private StoredPreview stored(ImportPreview preview) {
        return jsonMapper.readValue(preview.getPreview(), StoredPreview.class);
    }

    private static ImportPreviewResponse response(ImportPreview preview, StoredPreview stored, int page, int size) {
        List<ImportOperation> operations = stored.operations();
        int from = (int) Math.min((long) page * size, operations.size());
        int to = Math.min(from + size, operations.size());
        return new ImportPreviewResponse(
                preview.getId(),
                preview.getSource(),
                preview.getExpiresAt(),
                stored.summary(),
                new PageResponse<>(
                        operations.subList(from, to),
                        page,
                        size,
                        operations.size(),
                        (operations.size() + size - 1) / size));
    }
}
