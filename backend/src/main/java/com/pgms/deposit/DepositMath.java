package com.pgms.deposit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure arithmetic of applying deposit money: the settlement waterfall (funds applied to dues, oldest due first) and
 * splitting a payout or deduction across several source payments. No I/O, so it is exhaustively unit-tested.
 */
public final class DepositMath {

    private DepositMath() {
    }

    /**
     * Money that can be applied or paid out: what is left of one payment. {@code creditTransactionId} is set when the
     * money is rent paid for days not stayed (a RENT_CREDIT ledger entry) rather than the deposit itself.
     */
    public record Fund(Long paymentId, BigDecimal available, Long creditTransactionId) {

        public boolean isCredit() {
            return creditTransactionId != null;
        }
    }

    /** An open invoice balance, in the order dues are to be paid. */
    public record Due(Long invoiceId, BigDecimal balance) {
    }

    /** {@code amount} of {@code fund} applied to {@code invoiceId} (null for a payout). */
    public record Slice(Fund fund, Long invoiceId, BigDecimal amount) {
    }

    public record Waterfall(List<Slice> slices, BigDecimal applied, BigDecimal fundsLeft, BigDecimal duesLeft) {
    }

    /**
     * Applies funds in the given order to dues in the given order until either runs out. Deposit funds come first and
     * rent credit last, dues are ordered oldest first (the settlement invoice is the newest, so dues are cleared before
     * deductions).
     */
    public static Waterfall waterfall(List<Fund> funds, List<Due> dues) {
        List<Slice> slices = new ArrayList<>();
        BigDecimal[] fundLeft = funds.stream().map(Fund::available).toArray(BigDecimal[]::new);
        int f = 0;
        BigDecimal applied = BigDecimal.ZERO;
        BigDecimal duesLeft = BigDecimal.ZERO;
        for (Due due : dues) {
            BigDecimal owed = due.balance();
            while (owed.signum() > 0 && f < funds.size()) {
                if (fundLeft[f].signum() <= 0) {
                    f++;
                    continue;
                }
                BigDecimal take = owed.min(fundLeft[f]);
                slices.add(new Slice(funds.get(f), due.invoiceId(), take));
                fundLeft[f] = fundLeft[f].subtract(take);
                owed = owed.subtract(take);
                applied = applied.add(take);
            }
            duesLeft = duesLeft.add(owed.max(BigDecimal.ZERO));
        }
        BigDecimal fundsLeft = BigDecimal.ZERO;
        for (BigDecimal left : fundLeft) {
            fundsLeft = fundsLeft.add(left);
        }
        return new Waterfall(slices, applied, fundsLeft, duesLeft);
    }

    /** Splits {@code amount} across funds in order; throws when the funds do not cover it. */
    public static List<Slice> draw(List<Fund> funds, BigDecimal amount) {
        List<Slice> slices = new ArrayList<>();
        BigDecimal left = amount;
        for (Fund fund : funds) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal take = left.min(fund.available());
            if (take.signum() > 0) {
                slices.add(new Slice(fund, null, take));
                left = left.subtract(take);
            }
        }
        if (left.signum() > 0) {
            throw new IllegalStateException("Funds short by " + left);
        }
        return slices;
    }

    public static BigDecimal total(List<Fund> funds) {
        return funds.stream().map(Fund::available).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
