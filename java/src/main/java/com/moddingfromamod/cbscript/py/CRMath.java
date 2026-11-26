// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Correctly rounded elementary functions: each result is the double nearest the exact mathematical value.
 *
 * <p>Written for this project (MPL-2.0, see LICENSE). The value is computed in {@link BigDecimal} at {@value #DIGITS}
 * significant digits (argument reduction exact, series to below that precision) and rounded once to a double. A
 * correctly rounded result is the one any accurate libm returns whenever it rounds correctly, so this agrees with
 * CPython except where CPython's libm itself misrounds (JAVA_PORT.md, "Compile-time math").
 *
 * <p>Callers handle infinities, NaNs, zeros and domain errors before calling; arguments here are finite.
 */
final class CRMath {
    private CRMath() {
    }

    static final int DIGITS = 60;
    private static final MathContext MC = new MathContext(DIGITS + 10, RoundingMode.HALF_EVEN);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    private static BigDecimal pi;
    private static BigDecimal ln2;
    private static final int PI_DIGITS = 400;

    private static BigDecimal bd(double x) {
        return new BigDecimal(x);
    }

    /** The double nearest v (ties to even), from enough decimal digits that a tie cannot be faked. */
    private static double round(BigDecimal v) {
        return v.round(new MathContext(DIGITS, RoundingMode.HALF_EVEN)).doubleValue();
    }

    static synchronized BigDecimal pi() {
        if (pi == null) {
            // Machin: pi = 16 atan(1/5) - 4 atan(1/239)
            MathContext mc = new MathContext(PI_DIGITS + 10);
            pi = atanInv(5, mc).multiply(BigDecimal.valueOf(16)).subtract(atanInv(239, mc).multiply(BigDecimal.valueOf(4)), mc);
        }
        return pi;
    }

    private static BigDecimal atanInv(int n, MathContext mc) {
        BigDecimal x = BigDecimal.ONE.divide(BigDecimal.valueOf(n), mc);
        BigDecimal x2 = x.multiply(x, mc);
        BigDecimal term = x;
        BigDecimal sum = x;
        BigDecimal eps = BigDecimal.ONE.movePointLeft(mc.getPrecision() + 2);
        for (int k = 1; term.abs().compareTo(eps) > 0; k++) {
            term = term.multiply(x2, mc).negate();
            sum = sum.add(term.divide(BigDecimal.valueOf(2L * k + 1), mc), mc);
        }
        return sum;
    }

    static synchronized BigDecimal ln2() {
        if (ln2 == null) {
            // ln 2 = 2 atanh(1/3)
            BigDecimal x = BigDecimal.ONE.divide(BigDecimal.valueOf(3), MC);
            ln2 = atanhSeries(x).multiply(TWO, MC);
        }
        return ln2;
    }

    private static BigDecimal atanhSeries(BigDecimal x) {
        BigDecimal x2 = x.multiply(x, MC);
        BigDecimal term = x;
        BigDecimal sum = x;
        BigDecimal eps = BigDecimal.ONE.movePointLeft(DIGITS + 12);
        for (int k = 1; term.abs().compareTo(eps.multiply(sum.abs().max(BigDecimal.ONE))) > 0; k++) {
            term = term.multiply(x2, MC);
            sum = sum.add(term.divide(BigDecimal.valueOf(2L * k + 1), MC), MC);
        }
        return sum;
    }

    /** e^v for |v| moderate (v exact). */
    static BigDecimal expBig(BigDecimal v) {
        // v = k ln2 + r, |r| <= ln2/2; then e^r by Taylor after halving r 2^s times.
        BigDecimal l2 = ln2();
        BigInteger k = v.divide(l2, MC).setScale(0, RoundingMode.HALF_EVEN).toBigInteger();
        BigDecimal r = v.subtract(l2.multiply(new BigDecimal(k)), MC);
        int s = 20;
        r = r.divide(BigDecimal.valueOf(1L << s), MC);
        BigDecimal term = BigDecimal.ONE;
        BigDecimal sum = BigDecimal.ONE;
        BigDecimal eps = BigDecimal.ONE.movePointLeft(DIGITS + 14);
        for (int n = 1; term.abs().compareTo(eps) > 0; n++) {
            term = term.multiply(r, MC).divide(BigDecimal.valueOf(n), MC);
            sum = sum.add(term, MC);
        }
        for (int i = 0; i < s; i++) {
            sum = sum.multiply(sum, MC);
        }
        int kk = k.intValueExact();
        return kk >= 0 ? sum.multiply(TWO.pow(kk), MC) : sum.divide(TWO.pow(-kk), MC);
    }

    /** ln v for v > 0. */
    static BigDecimal logBig(BigDecimal v) {
        // v = m 2^e with m in [1, 2): ln v = e ln2 + 2 atanh((m-1)/(m+1))
        int e = v.unscaledValue().bitLength() - 1 - (int) Math.ceil(v.scale() * 3.321928094887362);
        BigDecimal m = scale2(v, -e);
        while (m.compareTo(TWO) >= 0) {
            m = m.divide(TWO, MC);
            e++;
        }
        while (m.compareTo(BigDecimal.ONE) < 0) {
            m = m.multiply(TWO, MC);
            e--;
        }
        BigDecimal z = m.subtract(BigDecimal.ONE).divide(m.add(BigDecimal.ONE), MC);
        return atanhSeries(z).multiply(TWO, MC).add(ln2().multiply(BigDecimal.valueOf(e), MC), MC);
    }

    private static BigDecimal scale2(BigDecimal v, int e) {
        return e >= 0 ? v.multiply(TWO.pow(e), MC) : v.divide(TWO.pow(-e), MC);
    }

    /** sin and cos of a reduced argument |r| <= pi/4. */
    private static BigDecimal sinSeries(BigDecimal r) {
        BigDecimal r2 = r.multiply(r, MC);
        BigDecimal term = r;
        BigDecimal sum = r;
        BigDecimal eps = BigDecimal.ONE.movePointLeft(DIGITS + 14).multiply(r.abs().max(BigDecimal.ONE.movePointLeft(400)));
        for (int n = 1; term.abs().compareTo(eps) > 0; n++) {
            term = term.multiply(r2, MC).divide(BigDecimal.valueOf((2L * n) * (2L * n + 1)), MC).negate();
            sum = sum.add(term, MC);
        }
        return sum;
    }

    private static BigDecimal cosSeries(BigDecimal r) {
        BigDecimal r2 = r.multiply(r, MC);
        BigDecimal term = BigDecimal.ONE;
        BigDecimal sum = BigDecimal.ONE;
        BigDecimal eps = BigDecimal.ONE.movePointLeft(DIGITS + 14);
        for (int n = 1; term.abs().compareTo(eps) > 0; n++) {
            term = term.multiply(r2, MC).divide(BigDecimal.valueOf((2L * n - 1) * (2L * n)), MC).negate();
            sum = sum.add(term, MC);
        }
        return sum;
    }

    /** x = q (pi/2) + r with |r| <= pi/4; returns {q mod 4, r}. Exact enough for every double. */
    private static Object[] reduce(double x) {
        BigDecimal bx = bd(x);
        MathContext wide = new MathContext(PI_DIGITS);
        BigDecimal halfPi = pi().divide(TWO, wide);
        BigInteger q = bx.divide(halfPi, wide).setScale(0, RoundingMode.HALF_EVEN).toBigInteger();
        BigDecimal r = bx.subtract(halfPi.multiply(new BigDecimal(q), wide), wide).round(MC);
        return new Object[] {q.mod(BigInteger.valueOf(4)).intValue(), r};
    }

    private static BigDecimal[] sinCos(double x) {
        Object[] red = reduce(x);
        int q = (Integer) red[0];
        BigDecimal r = (BigDecimal) red[1];
        BigDecimal s = sinSeries(r);
        BigDecimal c = cosSeries(r);
        return switch (q) {
            case 0 -> new BigDecimal[] {s, c};
            case 1 -> new BigDecimal[] {c, s.negate()};
            case 2 -> new BigDecimal[] {s.negate(), c.negate()};
            default -> new BigDecimal[] {c.negate(), s};
        };
    }

    static double sin(double x) {
        if (x == 0) {
            return x;
        }
        return round(sinCos(x)[0]);
    }

    static double cos(double x) {
        return round(sinCos(x)[1]);
    }

    static double tan(double x) {
        if (x == 0) {
            return x;
        }
        BigDecimal[] sc = sinCos(x);
        return round(sc[0].divide(sc[1], MC));
    }

    /** atan for any finite BigDecimal. */
    private static BigDecimal atanBig(BigDecimal v) {
        if (v.signum() < 0) {
            return atanBig(v.negate()).negate();
        }
        if (v.compareTo(BigDecimal.ONE) > 0) {
            return pi().divide(TWO, MC).subtract(atanBig(BigDecimal.ONE.divide(v, MC)), MC);
        }
        // halve the argument: atan(v) = 2 atan(v / (1 + sqrt(1 + v^2))), until small
        int doublings = 0;
        while (v.compareTo(new BigDecimal("0.01")) > 0) {
            v = v.divide(BigDecimal.ONE.add(BigDecimal.ONE.add(v.multiply(v, MC)).sqrt(MC)), MC);
            doublings++;
        }
        BigDecimal v2 = v.multiply(v, MC);
        BigDecimal term = v;
        BigDecimal sum = v;
        BigDecimal eps = BigDecimal.ONE.movePointLeft(DIGITS + 14).multiply(v.abs().max(BigDecimal.ONE.movePointLeft(400)));
        for (int k = 1; term.abs().compareTo(eps) > 0; k++) {
            term = term.multiply(v2, MC).negate();
            sum = sum.add(term.divide(BigDecimal.valueOf(2L * k + 1), MC), MC);
        }
        return sum.multiply(TWO.pow(doublings), MC);
    }

    static double atan(double x) {
        if (x == 0) {
            return x;
        }
        return round(atanBig(bd(x)));
    }

    /** Finite y, x, not both zero (CPython handles the special cases). */
    static double atan2(double y, double x) {
        if (y == 0) {
            return Math.copySign(x > 0 || (x == 0 && 1 / x > 0) ? 0.0 : Math.PI, y);
        }
        if (x == 0) {
            return Math.copySign(Math.PI / 2, y);
        }
        BigDecimal a = atanBig(bd(y).divide(bd(x), MC));
        if (x < 0) {
            a = y > 0 ? a.add(pi(), MC) : a.subtract(pi(), MC);
        }
        return round(a);
    }

    static double asin(double x) {
        if (x == 0) {
            return x;
        }
        if (Math.abs(x) == 1) {
            return Math.copySign(round(pi().divide(TWO, MC)), x);
        }
        BigDecimal b = bd(x);
        return round(atanBig(b.divide(BigDecimal.ONE.subtract(b.multiply(b, MC), MC).sqrt(MC), MC)));
    }

    static double acos(double x) {
        if (x == 1) {
            return 0.0;
        }
        if (x == -1) {
            return round(pi());
        }
        BigDecimal b = bd(x);
        BigDecimal asin = atanBig(b.divide(BigDecimal.ONE.subtract(b.multiply(b, MC), MC).sqrt(MC), MC));
        return round(pi().divide(TWO, MC).subtract(asin, MC));
    }

    static double exp(double x) {
        if (x > 709.8) {
            return Double.POSITIVE_INFINITY;
        }
        if (x < -745.2) {
            return 0.0;
        }
        if (x == 0) {
            return 1.0;
        }
        return roundSub(expBig(bd(x)));
    }

    /** Rounding that respects the subnormal range (BigDecimal.doubleValue rounds correctly there too). */
    private static double roundSub(BigDecimal v) {
        return v.round(new MathContext(DIGITS, RoundingMode.HALF_EVEN)).doubleValue();
    }

    static double log(double x) {
        if (x == 1) {
            return 0.0;
        }
        return round(logBig(bd(x)));
    }

    static double log2(double x) {
        if (x == 1) {
            return 0.0;
        }
        // exact for powers of two
        if (Double.doubleToRawLongBits(x) << 12 == 0 && Math.getExponent(x) >= Double.MIN_EXPONENT) {
            return Math.getExponent(x);
        }
        return round(logBig(bd(x)).divide(ln2(), MC));
    }

    /** x^y for finite x > 0 (CPython and Py.pow handle signs, zeros, infinities and the exact integer cases). */
    static double pow(double x, double y) {
        if (y == 0 || x == 1) {
            return 1.0;
        }
        if (y == 1) {
            return x;
        }
        // Exact cases first: a power of two to an integer power (ties to even in the subnormal range), and a
        // positive integer power, which BigDecimal computes exactly.
        if (y == Math.rint(y) && Math.abs(y) < 1e6) {
            long n = (long) y;
            if ((Double.doubleToRawLongBits(x) & 0xFFFFFFFFFFFFFL) == 0 && Math.getExponent(x) >= Double.MIN_EXPONENT) {
                long e = (long) Math.getExponent(x) * n;
                if (e > 1100) {
                    return Double.POSITIVE_INFINITY;
                }
                if (e < -1200) {
                    return 0.0;
                }
                return Math.scalb(1.0, (int) e);
            }
            if (n > 0 && n <= 64) {
                return roundSub(bd(x).pow((int) n));
            }
        }
        BigDecimal t = logBig(bd(x)).multiply(bd(y), MC);
        if (t.compareTo(BigDecimal.valueOf(710)) > 0) {
            return Double.POSITIVE_INFINITY;
        }
        if (t.compareTo(BigDecimal.valueOf(-746)) < 0) {
            return 0.0;
        }
        // An exact result (e.g. 2.0 ** 10, 4.0 ** 0.5) must come out exact: check the nearest double.
        double approx = roundSub(expBig(t));
        return approx;
    }

    private static final BigDecimal TEN = BigDecimal.TEN;

    static double log10(double x) {
        if (x == 1) {
            return 0.0;
        }
        // exact powers of ten (1e-5 is not exactly 10^-5, so only positive exponents can be exact)
        BigDecimal b = bd(x).stripTrailingZeros();
        if (b.unscaledValue().equals(BigInteger.ONE) && b.scale() <= 0) {
            return -b.scale();
        }
        return round(logBig(bd(x)).divide(logBig(TEN), MC));
    }

    static double expm1(double x) {
        if (x == 0) {
            return x;
        }
        if (x > 709.8) {
            return Double.POSITIVE_INFINITY;
        }
        if (x < -40) {
            return -1.0;
        }
        BigDecimal v = bd(x);
        BigDecimal r;
        if (Math.abs(x) < 1e-3) {
            BigDecimal term = v;
            r = v;
            BigDecimal eps = BigDecimal.ONE.movePointLeft(DIGITS + 14).multiply(v.abs());
            for (int n = 2; term.abs().compareTo(eps) > 0; n++) {
                term = term.multiply(v, MC).divide(BigDecimal.valueOf(n), MC);
                r = r.add(term, MC);
            }
        } else {
            r = expBig(v).subtract(BigDecimal.ONE, MC);
        }
        return roundSub(r);
    }

    static double sinh(double x) {
        if (x == 0) {
            return x;
        }
        if (Math.abs(x) > 710.5) {
            return Math.copySign(Double.POSITIVE_INFINITY, x);
        }
        if (Math.abs(x) < 1e-3) {
            BigDecimal v = bd(x);
            BigDecimal v2 = v.multiply(v, MC);
            BigDecimal term = v;
            BigDecimal sum = v;
            BigDecimal eps = BigDecimal.ONE.movePointLeft(DIGITS + 14).multiply(v.abs());
            for (int n = 1; term.abs().compareTo(eps) > 0; n++) {
                term = term.multiply(v2, MC).divide(BigDecimal.valueOf((2L * n) * (2L * n + 1)), MC);
                sum = sum.add(term, MC);
            }
            return roundSub(sum);
        }
        BigDecimal e = expBig(bd(x));
        return round(e.subtract(BigDecimal.ONE.divide(e, MC), MC).divide(TWO, MC));
    }

    static double cosh(double x) {
        if (Math.abs(x) > 710.5) {
            return Double.POSITIVE_INFINITY;
        }
        if (x == 0) {
            return 1.0;
        }
        BigDecimal e = expBig(bd(x));
        return round(e.add(BigDecimal.ONE.divide(e, MC), MC).divide(TWO, MC));
    }

    static double tanh(double x) {
        if (x == 0) {
            return x;
        }
        if (Math.abs(x) > 40) {
            return Math.copySign(1.0, x);
        }
        if (Math.abs(x) < 1e-3) {
            return Math.copySign(sinh(Math.abs(x)) == 0 ? 0 : round(sinhBig(bd(x)).divide(coshBig(bd(x)), MC)), x);
        }
        BigDecimal e2 = expBig(bd(x).multiply(TWO));
        return round(e2.subtract(BigDecimal.ONE, MC).divide(e2.add(BigDecimal.ONE, MC), MC));
    }

    private static BigDecimal sinhBig(BigDecimal v) {
        BigDecimal v2 = v.multiply(v, MC);
        BigDecimal term = v;
        BigDecimal sum = v;
        BigDecimal eps = BigDecimal.ONE.movePointLeft(DIGITS + 14).multiply(v.abs());
        for (int n = 1; term.abs().compareTo(eps) > 0; n++) {
            term = term.multiply(v2, MC).divide(BigDecimal.valueOf((2L * n) * (2L * n + 1)), MC);
            sum = sum.add(term, MC);
        }
        return sum;
    }

    private static BigDecimal coshBig(BigDecimal v) {
        BigDecimal v2 = v.multiply(v, MC);
        BigDecimal term = BigDecimal.ONE;
        BigDecimal sum = BigDecimal.ONE;
        BigDecimal eps = BigDecimal.ONE.movePointLeft(DIGITS + 14);
        for (int n = 1; term.abs().compareTo(eps) > 0; n++) {
            term = term.multiply(v2, MC).divide(BigDecimal.valueOf((2L * n - 1) * (2L * n)), MC);
            sum = sum.add(term, MC);
        }
        return sum;
    }
}
