package jp.co.ajs.afcsds.service;

import java.time.Clock;
import java.time.LocalDate;

/**
 * Process-local daily token accounting with a warn-once budget.
 *
 * <p>Cost guardrail: sums the tokens consumed per UTC day and reports when
 * the configured budget ({@code DAILY_TOKEN_BUDGET}; 0 = disabled) is first
 * crossed that day, so a runaway caller (e.g. a client stuck in a retry
 * loop) is noticed the same day rather than on the invoice. Callers log the
 * crossing — requests are never rejected, so a misconfigured budget can't
 * take the service down. Process-local by design (restarts reset the
 * count): this is an alerting aid, not billing.
 */
final class DailyTokenBudget {

    private final long budgetTokens;
    private final Clock clock;

    private LocalDate day;
    private long usedTokens;
    private boolean warned;

    /** @param clock decides what "today" is; production passes a UTC clock. */
    DailyTokenBudget(long budgetTokens, Clock clock) {
        this.budgetTokens = budgetTokens;
        this.clock = clock;
        this.day = LocalDate.now(clock);
    }

    /**
     * Records usage; returns {@code true} when this addition is the first to
     * push today's total over the budget (log the warning exactly then).
     */
    synchronized boolean record(long tokens) {
        LocalDate today = LocalDate.now(clock);
        if (!today.equals(day)) {
            day = today;
            usedTokens = 0;
            warned = false;
        }
        usedTokens += tokens;
        if (budgetTokens > 0 && !warned && usedTokens > budgetTokens) {
            warned = true;
            return true;
        }
        return false;
    }

    synchronized long usedToday() {
        return usedTokens;
    }
}
