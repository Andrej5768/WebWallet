package com.andrewcorp.webwallet.ledger.transaction;

import com.andrewcorp.webwallet.ledger.account.Account;
import com.andrewcorp.webwallet.ledger.category.Category;
import com.andrewcorp.webwallet.ledger.payee.Payee;
import com.andrewcorp.webwallet.ledger.tag.Tag;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An expense or income changes {@link #account} by {@link #amount}. A transfer takes {@link #amount} from
 * {@link #account} and puts {@link #toAmount}, in the currency of {@link #toAccount}, to {@link #toAccount}. Different
 * amounts in the same currency mean a fee. Amounts are always positive.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    /**
     * Range of plausible dates. Older dates are rejected: converting them would make the exchange rates be requested
     * for decades the NBU has no data for.
     */
    public static final LocalDate MIN_DATE = LocalDate.of(1990, 1, 1);

    public static final LocalDate MAX_DATE = LocalDate.of(2100, 1, 1);

    /** Ids are allocated in blocks, so that the inserts of an import can be batched. */
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "transactions_id")
    @SequenceGenerator(name = "transactions_id", sequenceName = "transactions_seq", allocationSize = 50)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType type;

    @Column(nullable = false)
    private LocalDate date;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(nullable = false)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id")
    private Account toAccount;

    private BigDecimal toAmount;

    private BigDecimal originalAmount;

    private String originalCurrency;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payee_id")
    private Payee payee;

    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionSource source = TransactionSource.MANUAL;

    private String externalId;

    /** Id of the bank operation on {@link #account}, see {@code OperationMatcher#normalizeBankId}. */
    private String bankId;

    /** Id of the bank operation on {@link #toAccount}: the incoming half of a transfer. */
    private String toBankId;

    /** Import id of the incoming half when an imported income was merged into this transfer. */
    private String toExternalId;

    /** A card payment the bank has only reserved; it may still change its amount or be cancelled. */
    private boolean hold;

    /** Merchant category code of a card payment. */
    private Short mcc;

    /** Classification of the operation by the bank, e.g. "Супермаркети та продукти". */
    private String bankCategory;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<TransactionSplit> splits = new ArrayList<>();

    @ManyToMany
    @JoinTable(
            name = "transaction_tags",
            joinColumns = @JoinColumn(name = "transaction_id"),
            inverseJoinColumns = @JoinColumn(name = "tag_id"))
    private Set<Tag> tags = new HashSet<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected Transaction() {}

    public Transaction(Long userId) {
        this.userId = userId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public void replaceSplits(List<SplitData> newSplits) {
        splits.clear();
        newSplits.forEach(
                split -> splits.add(new TransactionSplit(this, split.category(), split.amount(), split.comment())));
    }

    public void replaceTags(Set<Tag> newTags) {
        tags.clear();
        tags.addAll(newTags);
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public TransactionType getType() {
        return type;
    }

    public void setType(TransactionType type) {
        this.type = type;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Account getToAccount() {
        return toAccount;
    }

    public void setToAccount(Account toAccount) {
        this.toAccount = toAccount;
    }

    public BigDecimal getToAmount() {
        return toAmount;
    }

    public void setToAmount(BigDecimal toAmount) {
        this.toAmount = toAmount;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public void setOriginalAmount(BigDecimal originalAmount) {
        this.originalAmount = originalAmount;
    }

    public String getOriginalCurrency() {
        return originalCurrency;
    }

    public void setOriginalCurrency(String originalCurrency) {
        this.originalCurrency = originalCurrency;
    }

    public Payee getPayee() {
        return payee;
    }

    public void setPayee(Payee payee) {
        this.payee = payee;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public TransactionSource getSource() {
        return source;
    }

    public void setSource(TransactionSource source) {
        this.source = source;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public boolean isHold() {
        return hold;
    }

    public void setHold(boolean hold) {
        this.hold = hold;
    }

    public String getToExternalId() {
        return toExternalId;
    }

    public void setToExternalId(String toExternalId) {
        this.toExternalId = toExternalId;
    }

    public String getBankId() {
        return bankId;
    }

    public void setBankId(String bankId) {
        this.bankId = bankId;
    }

    public String getToBankId() {
        return toBankId;
    }

    public void setToBankId(String toBankId) {
        this.toBankId = toBankId;
    }

    public Short getMcc() {
        return mcc;
    }

    public void setMcc(Short mcc) {
        this.mcc = mcc;
    }

    public String getBankCategory() {
        return bankCategory;
    }

    public void setBankCategory(String bankCategory) {
        this.bankCategory = bankCategory;
    }

    public List<TransactionSplit> getSplits() {
        return splits;
    }

    public Set<Tag> getTags() {
        return tags;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public record SplitData(Category category, BigDecimal amount, String comment) {}
}
