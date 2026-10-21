package com.andrewcorp.webwallet.notification;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationSettingsService {

    private final NotificationSettingsRepository settings;

    public NotificationSettingsService(NotificationSettingsRepository settings) {
        this.settings = settings;
    }

    /** @param hour of Kyiv time when the reminders and the reports are sent */
    public record NotificationSettingsDto(
            @Min(0) @Max(23) int hour,
            boolean recurring,
            boolean budgets,
            boolean taxes,
            boolean banks,
            boolean forecast,
            boolean goals,
            boolean weekly,
            boolean monthly) {

        static NotificationSettingsDto from(NotificationSettings settings) {
            return new NotificationSettingsDto(
                    settings.getNotifyHour(),
                    settings.isRecurring(),
                    settings.isBudgets(),
                    settings.isTaxes(),
                    settings.isBanks(),
                    settings.isForecast(),
                    settings.isGoals(),
                    settings.isWeekly(),
                    settings.isMonthly());
        }
    }

    /** The settings of the user, the defaults when they were never changed. */
    @Transactional(readOnly = true)
    public NotificationSettings of(Long userId) {
        return settings.findById(userId).orElseGet(() -> new NotificationSettings(userId));
    }

    @Transactional(readOnly = true)
    public NotificationSettingsDto get(Long userId) {
        return NotificationSettingsDto.from(of(userId));
    }

    @Transactional
    public NotificationSettingsDto update(Long userId, NotificationSettingsDto request) {
        NotificationSettings current = settings.findById(userId).orElseGet(() -> new NotificationSettings(userId));
        current.setNotifyHour(request.hour());
        current.setRecurring(request.recurring());
        current.setBudgets(request.budgets());
        current.setTaxes(request.taxes());
        current.setBanks(request.banks());
        current.setForecast(request.forecast());
        current.setGoals(request.goals());
        current.setWeekly(request.weekly());
        current.setMonthly(request.monthly());
        return NotificationSettingsDto.from(settings.save(current));
    }
}
