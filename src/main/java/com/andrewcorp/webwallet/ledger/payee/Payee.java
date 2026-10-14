package com.andrewcorp.webwallet.ledger.payee;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Who received or paid the money: a shop, a service, a person.
 */
@Entity
@Table(name = "payees")
public class Payee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private Long userId;

    @Column(nullable = false)
    private String name;

    protected Payee() {
    }

    public Payee(Long userId, String name) {
        this.userId = userId;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public Long getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
