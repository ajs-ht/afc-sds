package jp.co.ajs.afcsds.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class DailyTokenBudgetTest {

    /** Fixed-zone clock whose instant tests can advance manually. */
    private static final class SettableClock extends Clock {
        private Instant instant = Instant.parse("2026-07-18T10:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Test
    void disabledBudgetNeverWarns() {
        DailyTokenBudget budget = new DailyTokenBudget(0, new SettableClock());

        assertThat(budget.record(1_000_000)).isFalse();
        assertThat(budget.usedToday()).isEqualTo(1_000_000);
    }

    @Test
    void warnsExactlyOnceWhenTheBudgetIsCrossed() {
        DailyTokenBudget budget = new DailyTokenBudget(100, new SettableClock());

        assertThat(budget.record(60)).isFalse();
        // 110 > 100: this addition crosses the budget.
        assertThat(budget.record(50)).isTrue();
        // Already warned today: keep counting, stay quiet.
        assertThat(budget.record(10)).isFalse();
        assertThat(budget.usedToday()).isEqualTo(120);
    }

    @Test
    void counterAndWarningResetOnANewUtcDay() {
        SettableClock clock = new SettableClock();
        DailyTokenBudget budget = new DailyTokenBudget(100, clock);
        assertThat(budget.record(150)).isTrue();

        clock.advance(Duration.ofDays(1));

        // Fresh day: the count restarts and the budget can warn again.
        assertThat(budget.record(50)).isFalse();
        assertThat(budget.usedToday()).isEqualTo(50);
        assertThat(budget.record(60)).isTrue();
    }
}
