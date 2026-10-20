package com.andrewcorp.webwallet.tax;

import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.category.CategoryKind;
import com.andrewcorp.webwallet.ledger.category.CategoryRepository;
import com.andrewcorp.webwallet.ledger.transaction.TransactionRepository;
import com.andrewcorp.webwallet.tax.TaxDtos.TaxSettingsRequest;
import com.andrewcorp.webwallet.tax.TaxDtos.TaxSettingsResponse;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The tax settings of a user: the rates of each year, the income categories, the marks of transactions and quarters. */
@Service
public class TaxSettingsService {

    private final TaxSettingsRepository settings;
    private final TaxIncomeCategoryRepository incomeCategories;
    private final TaxTransactionMarkRepository marks;
    private final TaxFopPaymentRepository payments;
    private final CategoryRepository categories;
    private final TransactionRepository transactions;

    public TaxSettingsService(
            TaxSettingsRepository settings,
            TaxIncomeCategoryRepository incomeCategories,
            TaxTransactionMarkRepository marks,
            TaxFopPaymentRepository payments,
            CategoryRepository categories,
            TransactionRepository transactions) {
        this.settings = settings;
        this.incomeCategories = incomeCategories;
        this.marks = marks;
        this.payments = payments;
        this.categories = categories;
        this.transactions = transactions;
    }

    /** The rates of the year that apply to the user. */
    @Transactional(readOnly = true)
    public TaxRates rates(Long userId, int year) {
        return find(userId, year).rates();
    }

    @Transactional(readOnly = true)
    public TaxSettingsResponse get(Long userId, int year) {
        return response(userId, year, find(userId, year));
    }

    /** Replaces the user's own values of the year; null values take the defaults. */
    @Transactional
    public TaxSettingsResponse update(Long userId, int year, TaxSettingsRequest request) {
        TaxSettings stored =
                settings.findById(new TaxSettings.Key(userId, year)).orElseGet(() -> new TaxSettings(userId, year));
        stored.setFopGroup(request.fopGroup() != null ? request.fopGroup().shortValue() : null);
        stored.setFopVat(request.fopVat());
        stored.setSingleTaxRate(request.singleTaxRate());
        stored.setFopMilitaryRate(request.fopMilitaryRate());
        stored.setSingleTaxMonthly(request.singleTaxMonthly());
        stored.setFopMilitaryMonthly(request.fopMilitaryMonthly());
        stored.setEsvEnabled(request.esvEnabled());
        stored.setEsvRate(request.esvRate());
        stored.setMinWage(request.minWage());
        stored.setPdfoRate(request.pdfoRate());
        stored.setInvestmentMilitaryRate(request.investmentMilitaryRate());
        stored.setForeignDocumentDefault(request.foreignDocumentDefault());
        settings.save(stored);
        return response(userId, year, stored);
    }

    /** The income categories whose income is income of the sole proprietor, with their subcategories. */
    @Transactional(readOnly = true)
    public Set<Long> incomeCategoryIds(Long userId) {
        return new LinkedHashSet<>(incomeCategories.findByUserId(userId).stream()
                .map(TaxIncomeCategory::getCategoryId)
                .toList());
    }

    /** Replaces the income categories; each must be an income category of the user. */
    @Transactional
    public List<Long> updateIncomeCategories(Long userId, List<Long> categoryIds) {
        Set<Long> unique = new LinkedHashSet<>(categoryIds);
        for (Long id : unique) {
            Category category = categories
                    .findByIdAndUserId(id, userId)
                    .orElseThrow(() -> ApiException.invalid("categoryIds", "error.notFound"));
            if (category.getKind() != CategoryKind.INCOME) {
                throw ApiException.invalid("categoryIds", "error.category.kind");
            }
        }
        incomeCategories.deleteAllOfUser(userId);
        unique.forEach(id -> incomeCategories.save(new TaxIncomeCategory(userId, id)));
        return List.copyOf(unique);
    }

    /** Adds a transaction of the user to the income of the sole proprietor, takes it out, or forgets the mark (null). */
    @Transactional
    public void mark(Long userId, Long transactionId, Boolean included) {
        transactions.findByIdAndUserId(transactionId, userId).orElseThrow(ApiException::notFound);
        if (included == null) {
            marks.findById(transactionId).ifPresent(marks::delete);
            return;
        }
        TaxTransactionMark mark =
                marks.findById(transactionId).orElseGet(() -> new TaxTransactionMark(transactionId, userId, included));
        mark.setIncluded(included);
        marks.save(mark);
    }

    @Transactional
    public void markPaid(Long userId, int year, int quarter, boolean paid) {
        TaxFopPayment.Key key = new TaxFopPayment.Key(userId, year, (short) quarter);
        if (paid) {
            if (!payments.existsById(key)) {
                payments.save(new TaxFopPayment(userId, year, quarter));
            }
        } else {
            payments.deleteById(key);
        }
    }

    private TaxSettings find(Long userId, int year) {
        return settings.findById(new TaxSettings.Key(userId, year)).orElseGet(() -> new TaxSettings(userId, year));
    }

    private TaxSettingsResponse response(Long userId, int year, TaxSettings stored) {
        TaxRates values = stored.rates();
        return new TaxSettingsResponse(
                year,
                values,
                TaxDefaults.of(year, values.fopGroup(), values.fopVat()),
                TaxDefaults.SOURCES,
                List.copyOf(incomeCategoryIds(userId)));
    }
}
