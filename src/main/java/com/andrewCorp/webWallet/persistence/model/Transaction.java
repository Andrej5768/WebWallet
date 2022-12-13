package com.andrewCorp.webWallet.persistence.model;

import com.andrewCorp.webWallet.persistence.enums.TransactionType;

import javax.persistence.*;
import java.math.BigDecimal;
import java.util.Date;
import java.util.Objects;

@Entity
@Table(name = "transaction")
public class Transaction {
        @Id
        @Column(unique = true, nullable = false)
        @GeneratedValue(strategy = GenerationType.AUTO)
        private Long id;
        private Long accountId;
        private BigDecimal amount;
        private String remark;
        private Date date;

        private TransactionType transactionType;
        public Transaction() {
        }

        public Transaction(Long accountId, BigDecimal amount, String remark, Date date, TransactionType transactionType) {
                this.accountId = accountId;
                this.amount = amount;
                this.remark = remark;
                this.date = date;
                this.transactionType = transactionType;
        }

        public Long getId() {
                return id;
        }

        public void setId(final Long id) {
                this.id = id;
        }

        public BigDecimal getAmount() {
                return amount;
        }

        public void setAmount(BigDecimal amount) {
                this.amount = amount;
        }

        public String getRemark() {
                return remark;
        }

        public void setRemark(String remark) {
                this.remark = remark;
        }

        public Long getAccountId() {
                return accountId;
        }

        public void setAccountId(Long accountId) {
                this.accountId = accountId;
        }

        public Date getDate() {
                return date;
        }

        public void setDate(Date date) {
                this.date = date;
        }

        public TransactionType getTransactionType() {
                return transactionType;
        }

        public void setTransactionType(TransactionType transactionType) {
                this.transactionType = transactionType;
        }

        @Override
        public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                Transaction that = (Transaction) o;
                return Objects.equals(id, that.id) && Objects.equals(accountId, that.accountId) && Objects.equals(amount, that.amount) && Objects.equals(date, that.date);
        }

        @Override
        public int hashCode() {
                return Objects.hash(id, accountId, amount, date);
        }

        @Override
        public String toString() {
                final StringBuffer sb = new StringBuffer("Transaction{");
                sb.append("id=").append(id);
                sb.append(", accountId=").append(accountId);
                sb.append(", amount=").append(amount);
                sb.append(", remark='").append(remark).append('\'');
                sb.append(", date=").append(date);
                sb.append('}');
                return sb.toString();
        }
}
