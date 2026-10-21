package com.andrewcorp.webwallet.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** What a user is notified about; a user without a row has the defaults: everything at 9:00. */
@Entity
@Table(name = "notification_settings")
public class NotificationSettings {

    public static final int DEFAULT_HOUR = 9;

    @Id
    private Long userId;

    /** Hour of Kyiv time when the reminders and the reports are sent. */
    @Column(nullable = false)
    private int notifyHour = DEFAULT_HOUR;

    @Column(nullable = false)
    private boolean recurring = true;

    @Column(nullable = false)
    private boolean budgets = true;

    @Column(nullable = false)
    private boolean taxes = true;

    @Column(nullable = false)
    private boolean banks = true;

    @Column(nullable = false)
    private boolean forecast = true;

    @Column(nullable = false)
    private boolean goals = true;

    @Column(nullable = false)
    private boolean weekly = true;

    @Column(nullable = false)
    private boolean monthly = true;

    protected NotificationSettings() {}

    public NotificationSettings(Long userId) {
        this.userId = userId;
    }

    public boolean isOn(NotificationKind kind) {
        return switch (kind) {
            case RECURRING -> recurring;
            case BUDGETS -> budgets;
            case TAXES -> taxes;
            case BANKS -> banks;
            case FORECAST -> forecast;
            case GOALS -> goals;
            case WEEKLY -> weekly;
            case MONTHLY -> monthly;
        };
    }

    public Long getUserId() {
        return userId;
    }

    public int getNotifyHour() {
        return notifyHour;
    }

    public void setNotifyHour(int notifyHour) {
        this.notifyHour = notifyHour;
    }

    public boolean isRecurring() {
        return recurring;
    }

    public void setRecurring(boolean recurring) {
        this.recurring = recurring;
    }

    public boolean isBudgets() {
        return budgets;
    }

    public void setBudgets(boolean budgets) {
        this.budgets = budgets;
    }

    public boolean isTaxes() {
        return taxes;
    }

    public void setTaxes(boolean taxes) {
        this.taxes = taxes;
    }

    public boolean isBanks() {
        return banks;
    }

    public void setBanks(boolean banks) {
        this.banks = banks;
    }

    public boolean isForecast() {
        return forecast;
    }

    public void setForecast(boolean forecast) {
        this.forecast = forecast;
    }

    public boolean isGoals() {
        return goals;
    }

    public void setGoals(boolean goals) {
        this.goals = goals;
    }

    public boolean isWeekly() {
        return weekly;
    }

    public void setWeekly(boolean weekly) {
        this.weekly = weekly;
    }

    public boolean isMonthly() {
        return monthly;
    }

    public void setMonthly(boolean monthly) {
        this.monthly = monthly;
    }
}
