// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Float to text as CPython does it: {@code repr} (the shortest string that round-trips, CPython's {@code 'r'}
 * format) and the {@code e}, {@code f}, {@code g} conversions of {@code %} and {@code format()} (correctly rounded,
 * half-even on the exact binary value, as David Gay's dtoa does).
 */
final class FloatFormat {
    private FloatFormat() {}

    /** Digits and decimal point position: value = 0.DIGITS * 10**decpt. */
    record Digits(String digits, int decpt) {}

    /** The shortest round-tripping digits of |x| (x finite, non-zero). */
    static Digits shortest(double x) {
        x = Math.abs(x);
        String s = Double.toString(x);
        int exp = 0;
        int e = s.indexOf('E');
        if (e >= 0) {
            exp = Integer.parseInt(s.substring(e + 1));
            s = s.substring(0, e);
        }
        int dot = s.indexOf('.');
        String intPart = s.substring(0, dot);
        String frac = s.substring(dot + 1);
        String all = intPart + frac;
        int decpt = intPart.length() + exp;
        int lead = 0;
        while (lead < all.length() - 1 && all.charAt(lead) == '0') {
            lead++;
        }
        all = all.substring(lead);
        decpt -= lead;
        int end = all.length();
        while (end > 1 && all.charAt(end - 1) == '0') {
            end--;
        }
        all = all.substring(0, end);
        if (all.length() == 2) {
            // Java may give two digits where one round-trips (it then prefers the closer two-digit decimal);
            // CPython always gives the shortest.
            BigDecimal one = new BigDecimal(x).round(new MathContext(1, RoundingMode.HALF_EVEN));
            if (Double.parseDouble(one.toString()) == x) {
                String d = one.unscaledValue().toString();
                int dp = d.length() - one.scale();
                int z = d.length();
                while (z > 1 && d.charAt(z - 1) == '0') {
                    z--;
                }
                return new Digits(d.substring(0, z), dp);
            }
        }
        return new Digits(all, decpt);
    }

    /** {@code repr(x)} / {@code str(x)}. */
    static String repr(double x) {
        if (Double.isNaN(x)) {
            return "nan";
        }
        if (Double.isInfinite(x)) {
            return x > 0 ? "inf" : "-inf";
        }
        if (x == 0) {
            return (1 / x < 0) ? "-0.0" : "0.0";
        }
        Digits d = shortest(x);
        String sign = x < 0 ? "-" : "";
        if (d.decpt() > -4 && d.decpt() <= 16) {
            return sign + fixed(d.digits(), d.decpt(), 0, true);
        }
        return sign + exponent(d.digits(), d.decpt(), 0, false, 'e');
    }

    /** Lays out digits in fixed notation with at least minFrac fractional digits (".0" when addDot0). */
    static String fixed(String digits, int decpt, int minFrac, boolean addDot0) {
        StringBuilder sb = new StringBuilder();
        String frac;
        if (decpt <= 0) {
            sb.append('0');
            frac = "0".repeat(-decpt) + digits;
        } else if (decpt >= digits.length()) {
            sb.append(digits).append("0".repeat(decpt - digits.length()));
            frac = "";
        } else {
            sb.append(digits, 0, decpt);
            frac = digits.substring(decpt);
        }
        if (frac.length() < minFrac) {
            frac = frac + "0".repeat(minFrac - frac.length());
        }
        if (!frac.isEmpty()) {
            sb.append('.').append(frac);
        } else if (addDot0) {
            sb.append(".0");
        }
        return sb.toString();
    }

    /** Lays out digits as d.ddde+XX with at least minFrac fractional digits. */
    static String exponent(String digits, int decpt, int minFrac, boolean forceDot, char e) {
        StringBuilder sb = new StringBuilder();
        sb.append(digits.charAt(0));
        String frac = digits.substring(1);
        if (frac.length() < minFrac) {
            frac = frac + "0".repeat(minFrac - frac.length());
        }
        if (!frac.isEmpty() || forceDot) {
            sb.append('.').append(frac);
        }
        int exp = decpt - 1;
        sb.append(e).append(exp < 0 ? '-' : '+');
        int a = Math.abs(exp);
        if (a < 10) {
            sb.append('0');
        }
        sb.append(a);
        return sb.toString();
    }

    /** |x| rounded to prec digits after the point (half-even on the exact value): digits + decpt. */
    static Digits fixedDigits(double x, int prec) {
        BigDecimal v = new BigDecimal(Math.abs(x)).setScale(prec, RoundingMode.HALF_EVEN);
        String d = v.unscaledValue().toString();
        if (d.equals("0")) {
            return new Digits("0", 1 - prec);
        }
        return new Digits(d, d.length() - prec);
    }

    /** |x| rounded to n significant digits (n >= 1): digits (exactly n, zero-padded) + decpt. */
    static Digits sigDigits(double x, int n) {
        if (x == 0) {
            return new Digits("0".repeat(n), 1);
        }
        BigDecimal v = new BigDecimal(Math.abs(x)).round(new MathContext(n, RoundingMode.HALF_EVEN));
        String d = v.unscaledValue().toString();
        int decpt = d.length() - v.scale();
        if (d.length() < n) {
            d = d + "0".repeat(n - d.length());
        } else if (d.length() > n) {
            d = d.substring(0, n);
        }
        return new Digits(d, decpt);
    }

    /** 'f' formatting of |x| with prec decimals (no sign). */
    static String formatF(double x, int prec, boolean alt) {
        Digits d = fixedDigits(x, prec);
        String s = fixed(d.digits(), d.decpt(), prec, false);
        if (alt && prec == 0) {
            s += ".";
        }
        return s;
    }

    /** 'e' formatting of |x| with prec decimals (no sign). */
    static String formatE(double x, int prec, boolean alt, char e) {
        Digits d = sigDigits(x, prec + 1);
        return exponent(d.digits(), d.decpt(), prec, alt, e);
    }

    /**
     * 'g' formatting of |x| (no sign) with precision prec; addDot0 for the no-type format spec (at least one
     * fractional digit in fixed notation).
     */
    static String formatG(double x, int prec, boolean alt, boolean addDot0, char e) {
        if (prec == 0) {
            prec = 1;
        }
        Digits d = sigDigits(x, prec);
        String digits = d.digits();
        if (!alt) {
            int z = digits.length();
            while (z > 1 && digits.charAt(z - 1) == '0') {
                z--;
            }
            digits = digits.substring(0, z);
        }
        int decpt = x == 0 ? 1 : d.decpt();
        if (decpt <= -4 || decpt > prec) {
            return exponent(digits, decpt, 0, alt, e);
        }
        int minFrac = alt ? Math.max(0, prec - decpt) : 0;
        String s = fixed(digits, decpt, minFrac, addDot0);
        if (alt && !s.contains(".")) {
            s += ".";
        }
        return s;
    }

    /** 'r' formatting of |x| (the repr digits) without sign; addDot0 as repr does. */
    static String formatR(double x) {
        String r = repr(Math.abs(x));
        return r;
    }
}
