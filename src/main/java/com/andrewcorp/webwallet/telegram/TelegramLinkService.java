package com.andrewcorp.webwallet.telegram;

import com.andrewcorp.webwallet.auth.token.TokenHasher;
import com.andrewcorp.webwallet.common.error.ApiException;
import com.andrewcorp.webwallet.config.AppProperties;
import com.andrewcorp.webwallet.ledger.account.AccountService;
import com.andrewcorp.webwallet.telegram.TelegramApi.From;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Connects a Telegram chat to a user by the one-time code of a t.me link, and disconnects it. */
@Service
public class TelegramLinkService {

    static final Duration CODE_TTL = Duration.ofMinutes(15);

    private final TelegramLinkRepository links;
    private final TelegramLinkCodeRepository codes;
    private final AccountService accountService;
    private final AppProperties properties;
    private final Clock clock;

    public TelegramLinkService(
            TelegramLinkRepository links,
            TelegramLinkCodeRepository codes,
            AccountService accountService,
            AppProperties properties,
            Clock clock) {
        this.links = links;
        this.codes = codes;
        this.accountService = accountService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param enabled            whether the bot is configured on the server
     * @param quickEntryAccountId account of the operations written without naming one; the first card when null
     */
    public record TelegramStatus(
            boolean enabled,
            @Schema(nullable = true) String botUsername,
            boolean linked,
            @Schema(nullable = true) String username,
            @Schema(nullable = true) String firstName,
            @Schema(nullable = true) Instant linkedAt,
            @Schema(nullable = true) Long quickEntryAccountId) {}

    /** @param url opens the bot with the code; it works once, until {@code expiresAt} */
    public record TelegramLinkResponse(String url, Instant expiresAt) {}

    public record TelegramSettingsRequest(Long quickEntryAccountId) {}

    /** What connecting a chat by a code came to. */
    enum LinkResult {
        LINKED,
        INVALID,
        /** The chat belongs to another user, who has to disconnect it first. */
        TAKEN
    }

    @Transactional(readOnly = true)
    public TelegramStatus status(Long userId) {
        boolean enabled = properties.telegram().enabled();
        String bot = enabled ? properties.telegram().botUsername() : null;
        return links.findById(userId)
                .map(link -> new TelegramStatus(
                        enabled,
                        bot,
                        true,
                        link.getUsername(),
                        link.getFirstName(),
                        link.getLinkedAt(),
                        link.getQuickEntryAccountId()))
                .orElseGet(() -> new TelegramStatus(enabled, bot, false, null, null, null, null));
    }

    /** A new one-time link to the bot; it replaces the previous one. */
    @Transactional
    public TelegramLinkResponse createLink(Long userId) {
        if (!properties.telegram().enabled()) {
            throw ApiException.notFound();
        }
        String code = TokenHasher.newToken();
        Instant expiresAt = clock.instant().plus(CODE_TTL);
        TelegramLinkCode stored = codes.findById(userId).orElse(null);
        if (stored == null) {
            codes.save(new TelegramLinkCode(userId, TokenHasher.hash(code), expiresAt));
        } else {
            stored.setCodeHash(TokenHasher.hash(code));
            stored.setExpiresAt(expiresAt);
        }
        return new TelegramLinkResponse(
                "https://t.me/" + properties.telegram().botUsername() + "?start=" + code, expiresAt);
    }

    @Transactional
    public TelegramStatus updateSettings(Long userId, TelegramSettingsRequest request) {
        TelegramLink link = links.findById(userId).orElseThrow(ApiException::notFound);
        if (request.quickEntryAccountId() != null
                && accountService.find(userId, request.quickEntryAccountId()).isArchived()) {
            throw ApiException.invalid("quickEntryAccountId", "validation.accountArchived");
        }
        link.setQuickEntryAccountId(request.quickEntryAccountId());
        return status(userId);
    }

    /** Disconnects the chat of the user; returns it, so that the bot can say goodbye there. */
    @Transactional
    public Optional<Long> unlink(Long userId) {
        codes.deleteById(userId);
        Optional<TelegramLink> link = links.findById(userId);
        link.ifPresent(links::delete);
        return link.map(TelegramLink::getChatId);
    }

    /** Disconnects the chat by /stop in it. */
    @Transactional
    public boolean unlinkChat(long chatId) {
        Optional<TelegramLink> link = links.findByChatId(chatId);
        link.ifPresent(links::delete);
        return link.isPresent();
    }

    @Transactional(readOnly = true)
    public Optional<TelegramLink> byChat(long chatId) {
        return links.findByChatId(chatId);
    }

    /** Connects the chat to the user of the code; a user who had another chat moves to this one. */
    @Transactional
    LinkResult link(String code, long chatId, From from) {
        Optional<TelegramLinkCode> found = codes.findByCodeHash(TokenHasher.hash(code));
        if (found.isEmpty() || found.get().getExpiresAt().isBefore(clock.instant())) {
            return LinkResult.INVALID;
        }
        Long userId = found.get().getUserId();
        Optional<TelegramLink> chatLink = links.findByChatId(chatId);
        if (chatLink.isPresent() && !chatLink.get().getUserId().equals(userId)) {
            return LinkResult.TAKEN;
        }
        codes.delete(found.get());
        TelegramLink link = links.findById(userId).orElseGet(() -> new TelegramLink(userId, chatId));
        link.setChatId(chatId);
        link.setUsername(from != null ? from.username() : null);
        link.setFirstName(from != null ? from.firstName() : null);
        link.setLinkedAt(clock.instant());
        links.save(link);
        return LinkResult.LINKED;
    }
}
