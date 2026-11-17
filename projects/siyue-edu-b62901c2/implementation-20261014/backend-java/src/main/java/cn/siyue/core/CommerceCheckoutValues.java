package cn.siyue.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.List;

/** Canonicalizes checkout money and identity inputs. */
final class CommerceCheckoutValues {
    static final BigDecimal ZERO = new BigDecimal("0.00");

    private CommerceCheckoutValues() { }

    static String clean(String value, int maximum, String label) {
        if (value == null || value.isBlank() || value.trim().length() > maximum) { invalid(label + "无效"); }
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFKC);
    }

    static String optional(String value, int maximum) {
        return value == null || value.isBlank() ? null : clean(value, maximum, "学生年级");
    }

    static String normalizePhone(String value) {
        String normalized = clean(value, 30, "家长手机号").replaceAll("[\\s()\\-]", "");
        if (!normalized.matches("1[3-9][0-9]{9}")) { invalid("家长手机号无效"); }
        return normalized;
    }

    static BigDecimal money(BigDecimal value) { return value.setScale(2, RoundingMode.UNNECESSARY); }

    static long cents(BigDecimal value) { return money(value).movePointRight(2).longValueExact(); }

    static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(ZERO, BigDecimal::add).setScale(2, RoundingMode.UNNECESSARY);
    }

    static long[] allocate(long amount, List<Long> weights) {
        long total = weights.stream().mapToLong(Long::longValue).sum();
        if (amount <= 0 || total <= 0) { return new long[weights.size()]; }
        long[] shares = new long[weights.size()];
        long[] remainders = new long[weights.size()];
        long allocated = 0;
        for (int index = 0; index < weights.size(); index++) {
            long product = Math.multiplyExact(amount, weights.get(index));
            shares[index] = product / total;
            remainders[index] = product % total;
            allocated += shares[index];
        }
        while (allocated < amount) {
            int winner = 0;
            for (int index = 1; index < remainders.length; index++) {
                if (remainders[index] > remainders[winner]) { winner = index; }
            }
            shares[winner]++;
            remainders[winner] = -1;
            allocated++;
        }
        return shares;
    }

    static void invalid(String message) { throw new BusinessException(ErrorCode.INVALID_INPUT, message); }
}
