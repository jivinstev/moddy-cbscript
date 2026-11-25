// Translated from the GNU C Library 2.39 (sysdeps/ieee754/dbl-64). NOT covered by this repository's LICENSE: the
// sin/cos/tan/atan/atan2/asin/acos/__branred parts are IBM Accurate Mathematical Library code, LGPL-2.1-or-later;
// exp/log/log2/pow are Arm optimized-routines (MIT/Apache-2.0); log10/expm1/sinh/cosh/tanh are fdlibm (Sun notice).
// See java/THIRD_PARTY.md. Licensing to be confirmed before release.
package com.moddingfromamod.cbscript.py;

import static com.moddingfromamod.cbscript.py.LibMTables.*;
import static java.lang.Math.fma;

/**
 * Bit-exact port of the GNU C Library's double-precision {@code exp log log2 log10 pow sin cos tan asin acos atan
 * atan2 sinh cosh tanh expm1} (glibc 2.39, {@code sysdeps/ieee754/dbl-64}), as CPython calls them on x86-64 Linux.
 *
 * <p>Why not {@link Math} or {@link StrictMath}: glibc is not correctly rounded (it misrounds roughly one argument
 * in a thousand for sin/exp/pow, and far more for sinh/cosh/tanh), and Java's functions round differently again,
 * so the only way to reproduce CPython's last bit is to run glibc's own algorithms on glibc's own tables.
 *
 * <p>On a CPU with FMA and AVX2 (every x86-64 CPU since 2013), glibc selects the variants of these functions that
 * were compiled with {@code -mfma -mavx2}: GCC then contracts every {@code a * b + c} whose product has no other
 * use into one fused multiply-add. The code below spells out each such contraction with {@link Math#fma}, so it
 * computes exactly what that machine code computes. {@code __branred}, {@code __ieee754_log10}, {@code sinh},
 * {@code cosh} and {@code tanh} have no FMA variant and are ported without contraction (they call the FMA variants
 * of exp, log and expm1). Verified against CPython 3.13 / glibc 2.39 on millions of random arguments
 * ({@code PyEvalDifferentialTest} carries a seeded sample of them).
 *
 * <p>Error handling (errno) is not modelled: callers apply CPython's rules to the returned value.
 *
 * <p>Exactness assumes the reference CPython runs on x86-64 Linux with glibc 2.35 or later on a CPU with FMA (as the
 * goldens are made). Without FMA, glibc runs differently rounded code and CPython itself gives different last bits;
 * macOS and Windows libms differ again.
 *
 * <p>Provenance (check before release): exp, log, log2, pow and their tables are Arm's optimized-routines code
 * (also published by Arm under MIT / Apache-2.0); log10, expm1, sinh, cosh and tanh are Sun's fdlibm (permissive
 * notice); sin, cos, tan, atan, atan2, asin, acos, __branred and their tables are IBM's Accurate Mathematical Library
 * as distributed in glibc under the LGPL-2.1-or-later.
 */
final class LibM {
    private LibM() {}

    private static long bits(double x) {
        return Double.doubleToRawLongBits(x);
    }

    private static double dbl(long b) {
        return Double.longBitsToDouble(b);
    }

    private static int top12(double x) {
        return (int) (bits(x) >>> 52);
    }

    private static int hi32(double x) {
        return (int) (bits(x) >>> 32);
    }

    private static int lo32(double x) {
        return (int) bits(x);
    }

    private static boolean ult(int a, int b) {
        return Integer.compareUnsigned(a, b) < 0;
    }

    private static boolean ultL(long a, long b) {
        return Long.compareUnsigned(a, b) < 0;
    }

    // ------------------------------------------------------------------------------------------------------------
    // exp (e_exp.c), shared with pow's exp_inline
    // ------------------------------------------------------------------------------------------------------------

    private static final double INV_LN2_N = EXP[0];
    private static final double SHIFT = EXP[1];
    private static final double NEG_LN2_HI_N = EXP[2];
    private static final double NEG_LN2_LO_N = EXP[3];
    private static final double C2 = EXP[4];
    private static final double C3 = EXP[5];
    private static final double C4 = EXP[6];
    private static final double C5 = EXP[7];
    private static final int EXP_N = 128;
    private static final long SIGN_BIAS = 0x800L << 7;

    static double exp(double x) {
        return expInline(x, 0.0, 0L, false);
    }

    /** {@code exp_inline} of e_pow.c (with xtail and sign) and {@code __exp} of e_exp.c (without). */
    private static double expInline(double x, double xtail, long signBias, boolean fromPow) {
        int abstop = top12(x) & 0x7ff;
        if (!ult(abstop - 0x3c9, 0x408 - 0x3c9)) {
            if (!ult(abstop - 0x3c9, 0x80000000)) {
                double one = 1.0 + x;
                return signBias != 0 ? -one : one;
            }
            if (abstop >= 0x409) {
                if (!fromPow) {
                    if (bits(x) == bits(Double.NEGATIVE_INFINITY)) {
                        return 0.0;
                    }
                    if (abstop >= 0x7ff) {
                        return 1.0 + x;
                    }
                }
                boolean neg = signBias != 0;
                if (bits(x) < 0) {
                    return neg ? -0.0 : 0.0;
                }
                return neg ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
            }
            abstop = 0;
        }
        double kd = fma(INV_LN2_N, x, SHIFT);
        long ki = bits(kd);
        kd -= SHIFT;
        double r = fma(kd, NEG_LN2_LO_N, fma(kd, NEG_LN2_HI_N, x));
        if (fromPow) {
            r += xtail;
        }
        int idx = 2 * (int) (ki & (EXP_N - 1));
        long top = (ki + signBias) << (52 - 7);
        double tail = dbl(EXP_TAB_BITS[idx]);
        long sbits = EXP_TAB_BITS[idx + 1] + top;
        double r2 = r * r;
        double tmp = fma(r2 * r2, fma(r, C5, C4), fma(r2, fma(r, C3, C2), tail + r));
        if (abstop == 0) {
            return expSpecialCase(tmp, sbits, ki, fromPow);
        }
        double scale = dbl(sbits);
        return fma(scale, tmp, scale);
    }

    private static double expSpecialCase(double tmp, long sbits, long ki, boolean fromPow) {
        if ((ki & 0x80000000L) == 0) {
            sbits -= 1009L << 52;
            double scale = dbl(sbits);
            return 0x1p1009 * fma(scale, tmp, scale);
        }
        sbits += 1022L << 52;
        double scale = dbl(sbits);
        // scale * tmp is shared with the branch below, so GCC computes it once and does not contract it.
        double st = scale * tmp;
        double y = scale + st;
        if (fromPow ? Math.abs(y) < 1.0 : y < 1.0) {
            double one = (fromPow && y < 0.0) ? -1.0 : 1.0;
            double lo = scale - y + st;
            double hi = one + y;
            lo = one - hi + y + lo;
            y = (hi + lo) - one;
            if (y == 0.0) {
                y = fromPow ? dbl(sbits & 0x8000000000000000L) : 0.0;
            }
        }
        return 0x1p-1022 * y;
    }

    // ------------------------------------------------------------------------------------------------------------
    // log (e_log.c)
    // ------------------------------------------------------------------------------------------------------------

    private static final double LN2_HI = LOG[0];
    private static final double LN2_LO = LOG[1];
    private static final double[] LOG_A = {LOG[2], LOG[3], LOG[4], LOG[5], LOG[6]};
    private static final double[] LOG_B = java.util.Arrays.copyOfRange(LOG, 7, 18);

    static double log(double x) {
        long ix = bits(x);
        int top = (int) (ix >>> 48);
        long lo1 = bits(1.0 - 0x1p-4);
        long hi1 = bits(1.0 + 0x1.09p-4);
        if (ultL(ix - lo1, hi1 - lo1)) {
            if (ix == bits(1.0)) {
                return 0;
            }
            double[] b = LOG_B;
            double r = x - 1.0;
            double r2 = r * r;
            double r3 = r * r2;
            double inner3 = fma(r3, b[10], fma(r2, b[9], fma(r, b[8], b[7])));
            double inner2 = fma(r3, inner3, fma(r2, b[6], fma(r, b[5], b[4])));
            double inner1 = fma(r3, inner2, fma(r2, b[3], fma(r, b[2], b[1])));
            double w = r * 0x1p27;
            double rhi = r + w - w;
            double rlo = r - rhi;
            double rr = rhi * rhi; // w = rhi*rhi*B[0] is contracted into both of its uses
            double hi = fma(rr, b[0], r);
            double lo = fma(rr, b[0], r - hi);
            lo = fma(b[0] * rlo, rhi + r, lo);
            double y = fma(r3, inner1, lo); // y = r3 * (...) is only used in y += lo
            y += hi;
            return y;
        }
        if (!ult(top - 0x0010, 0x7ff0 - 0x0010)) {
            if (ix * 2 == 0) {
                return Double.NEGATIVE_INFINITY;
            }
            if (ix == bits(Double.POSITIVE_INFINITY)) {
                return x;
            }
            if ((top & 0x8000) != 0 || (top & 0x7ff0) == 0x7ff0) {
                return Double.NaN;
            }
            ix = bits(x * 0x1p52);
            ix -= 52L << 52;
        }
        long tmp = ix - 0x3fe6000000000000L;
        int i = (int) ((tmp >>> (52 - 7)) % 128);
        int k = (int) (tmp >> 52);
        long iz = ix - (tmp & 0xfffL << 52);
        double invc = LOG_TAB[2 * i];
        double logc = LOG_TAB[2 * i + 1];
        double z = dbl(iz);
        double r = fma(z, invc, -1.0);
        double kd = k;
        double w = fma(kd, LN2_HI, logc);
        double hi = w + r;
        double lo = fma(kd, LN2_LO, w - hi + r);
        double r2 = r * r;
        double[] a = LOG_A;
        double poly = fma(r2, fma(r, a[4], a[3]), fma(r, a[2], a[1]));
        double y = fma(r * r2, poly, fma(r2, a[0], lo)) + hi;
        return y;
    }

    // ------------------------------------------------------------------------------------------------------------
    // log2 (e_log2.c)
    // ------------------------------------------------------------------------------------------------------------

    static double log2(double x) {
        double invLn2hi = LOG2[0];
        double invLn2lo = LOG2[1];
        double[] a = java.util.Arrays.copyOfRange(LOG2, 2, 8);
        double[] b = java.util.Arrays.copyOfRange(LOG2, 8, 18);
        long ix = bits(x);
        int top = (int) (ix >>> 48);
        long lo1 = bits(1.0 - 0x1.5b51p-5);
        long hi1 = bits(1.0 + 0x1.6ab2p-5);
        if (ultL(ix - lo1, hi1 - lo1)) {
            if (ix == bits(1.0)) {
                return 0;
            }
            double r = x - 1.0;
            double hi = r * invLn2hi;
            double lo = fma(r, invLn2lo, fma(r, invLn2hi, -hi));
            double r2 = r * r;
            double r4 = r2 * r2;
            // p = r2 * (B0 + r * B1) has two additive uses, so GCC contracts it into both.
            double pa = fma(r, b[1], b[0]);
            double y = fma(r2, pa, hi);
            lo += fma(r2, pa, hi - y);
            double q1 = fma(r2, fma(r, b[5], b[4]), fma(r, b[3], b[2]));
            double q2 = fma(r2, fma(r, b[9], b[8]), fma(r, b[7], b[6]));
            lo = fma(r4, fma(r4, q2, q1), lo);
            y += lo;
            return y;
        }
        if (!ult(top - 0x0010, 0x7ff0 - 0x0010)) {
            if (ix * 2 == 0) {
                return Double.NEGATIVE_INFINITY;
            }
            if (ix == bits(Double.POSITIVE_INFINITY)) {
                return x;
            }
            if ((top & 0x8000) != 0 || (top & 0x7ff0) == 0x7ff0) {
                return Double.NaN;
            }
            ix = bits(x * 0x1p52);
            ix -= 52L << 52;
        }
        long tmp = ix - 0x3fe6000000000000L;
        int i = (int) ((tmp >>> (52 - 6)) % 64);
        int k = (int) (tmp >> 52);
        long iz = ix - (tmp & 0xfffL << 52);
        double invc = LOG2_TAB[2 * i];
        double logc = LOG2_TAB[2 * i + 1];
        double z = dbl(iz);
        double kd = k;
        double r = fma(z, invc, -1.0);
        double t1 = r * invLn2hi;
        double t2 = fma(r, invLn2lo, fma(r, invLn2hi, -t1));
        double t3 = kd + logc;
        double hi = t3 + t1;
        double lo = t3 - hi + t1 + t2;
        double r2 = r * r;
        double r4 = r2 * r2;
        double p = fma(r4, fma(r, a[5], a[4]), fma(r2, fma(r, a[3], a[2]), fma(r, a[1], a[0])));
        return fma(r2, p, lo) + hi;
    }

    // ------------------------------------------------------------------------------------------------------------
    // log10 (e_log10.c, no FMA variant; calls the FMA log)
    // ------------------------------------------------------------------------------------------------------------

    static double log10(double x) {
        final double two54 = 1.80143985094819840000e+16;
        final double ivln10 = 4.34294481903251816668e-01;
        final double log10_2hi = 3.01029995663611771306e-01;
        final double log10_2lo = 3.69423907715893078616e-13;
        long hx = bits(x);
        int k = 0;
        if (hx < 0x0010000000000000L) {
            if ((hx & 0x7fffffffffffffffL) == 0) {
                return -two54 / Math.abs(x);
            }
            if (hx < 0) {
                return (x - x) / (x - x);
            }
            k -= 54;
            x *= two54;
            hx = bits(x);
        }
        if (hx >= 0x7ff0000000000000L) {
            return x + x;
        }
        k += (int) ((hx >> 52) - 1023);
        long i = ((long) k & 0x8000000000000000L) >>> 63;
        hx = (hx & 0x000fffffffffffffL) | ((0x3ff - i) << 52);
        double y = (double) (k + i);
        x = dbl(hx);
        double z = y * log10_2lo + ivln10 * log(x);
        return z + y * log10_2hi;
    }

    // ------------------------------------------------------------------------------------------------------------
    // pow (e_pow.c)
    // ------------------------------------------------------------------------------------------------------------

    /** log_inline of e_pow.c: returns {y, tail}. */
    private static double[] logInline(long ix) {
        double ln2hi = POWLOG[0];
        double ln2lo = POWLOG[1];
        long tmp = ix - 0x3fe6955500000000L;
        int i = (int) ((tmp >>> (52 - 7)) % 128);
        int k = (int) (tmp >> 52);
        long iz = ix - (tmp & 0xfffL << 52);
        double z = dbl(iz);
        double kd = k;
        double invc = POWLOG_TAB[4 * i];
        double logc = POWLOG_TAB[4 * i + 2];
        double logctail = POWLOG_TAB[4 * i + 3];
        double r = fma(z, invc, -1.0);
        double t1 = fma(kd, ln2hi, logc);
        double t2 = t1 + r;
        double lo1 = fma(kd, ln2lo, logctail);
        double lo2 = t1 - t2 + r;
        double ar = POWLOG[2] * r;
        double ar2 = r * ar;
        double ar3 = r * ar2;
        double hi = t2 + ar2;
        double lo3 = fma(ar, r, -ar2);
        double lo4 = t2 - hi + ar2;
        double[] a = POWLOG;
        // A[k] is POWLOG[2 + k]
        double poly = fma(ar2, fma(ar2, fma(r, a[8], a[7]), fma(r, a[6], a[5])), fma(r, a[4], a[3]));
        double lo = fma(ar3, poly, lo1 + lo2 + lo3 + lo4);
        double y = hi + lo;
        return new double[] {y, hi - y + lo};
    }

    private static int checkint(long iy) {
        int e = (int) (iy >>> 52 & 0x7ff);
        if (e < 0x3ff) {
            return 0;
        }
        if (e > 0x3ff + 52) {
            return 2;
        }
        if ((iy & ((1L << (0x3ff + 52 - e)) - 1)) != 0) {
            return 0;
        }
        if ((iy & (1L << (0x3ff + 52 - e))) != 0) {
            return 1;
        }
        return 2;
    }

    private static boolean zeroinfnan(long i) {
        return !ultL(2 * i - 1, 2 * bits(Double.POSITIVE_INFINITY) - 1);
    }

    static double pow(double x, double y) {
        long signBias = 0;
        long ix = bits(x);
        long iy = bits(y);
        int topx = top12(x);
        int topy = top12(y);
        if (!ult(topx - 0x001, 0x7ff - 0x001) || !ult((topy & 0x7ff) - 0x3be, 0x43e - 0x3be)) {
            if (zeroinfnan(iy)) {
                if (2 * iy == 0) {
                    return 1.0;
                }
                if (ix == bits(1.0)) {
                    return 1.0;
                }
                if (ultL(2 * bits(Double.POSITIVE_INFINITY), 2 * ix)
                        || ultL(2 * bits(Double.POSITIVE_INFINITY), 2 * iy)) {
                    return x + y;
                }
                if (2 * ix == 2 * bits(1.0)) {
                    return 1.0;
                }
                if (ultL(2 * ix, 2 * bits(1.0)) == (iy >= 0)) {
                    return 0.0;
                }
                return y * y;
            }
            if (zeroinfnan(ix)) {
                double x2 = x * x;
                if (ix < 0 && checkint(iy) == 1) {
                    x2 = -x2;
                }
                return iy < 0 ? 1 / x2 : x2;
            }
            if (ix < 0) {
                int yint = checkint(iy);
                if (yint == 0) {
                    return Double.NaN;
                }
                if (yint == 1) {
                    signBias = SIGN_BIAS;
                }
                ix &= 0x7fffffffffffffffL;
                topx &= 0x7ff;
            }
            if (!ult((topy & 0x7ff) - 0x3be, 0x43e - 0x3be)) {
                if (ix == bits(1.0)) {
                    return 1.0;
                }
                if ((topy & 0x7ff) < 0x3be) {
                    return ultL(bits(1.0), ix) ? 1.0 + y : 1.0 - y;
                }
                return (ultL(bits(1.0), ix)) == (topy < 0x800) ? Double.POSITIVE_INFINITY : 0.0;
            }
            if (topx == 0) {
                ix = bits(x * 0x1p52);
                ix &= 0x7fffffffffffffffL;
                ix -= 52L << 52;
            }
        }
        double[] l = logInline(ix);
        double hi = l[0];
        double lo = l[1];
        double ehi = y * hi;
        double elo = fma(y, lo, fma(y, hi, -ehi));
        return expInline(ehi, elo, signBias, true);
    }

    // ------------------------------------------------------------------------------------------------------------
    // sin, cos (s_sin.c) and __branred (branred.c, no FMA variant)
    // ------------------------------------------------------------------------------------------------------------

    private static final double S1 = -0x1.5555555555555p-3;
    private static final double S2 = 0x1.1111111110ECEp-7;
    private static final double S3 = -0x1.A01A019DB08B8p-13;
    private static final double S4 = 0x1.71DE27B9A7ED9p-19;
    private static final double S5 = -0x1.ADDFFC2FCDF59p-26;
    private static final double BIG = 0x1.8000000000000p45;
    private static final double HP0 = 0x1.921FB54442D18p0;
    private static final double HP1 = 0x1.1A62633145C07p-54;
    private static final double MP1 = 0x1.921FB58000000p0;
    private static final double MP2 = -0x1.DDE973C000000p-27;
    private static final double PP3 = -0x1.CB3B398000000p-55;
    private static final double PP4 = -0x1.d747f23e32ed7p-83;
    private static final double HPINV = 0x1.45F306DC9C883p-1;
    private static final double TOINT = 0x1.8000000000000p52;
    private static final double SN3 = -1.66666666666664880952546298448555E-01;
    private static final double SN5 = 8.33333214285722277379541354343671E-03;
    private static final double CS2 = 4.99999999999999999999950396842453E-01;
    private static final double CS4 = -4.16666666666664434524222570944589E-02;
    private static final double CS6 = 1.38888874007937613028114285595617E-03;

    private static double taylorSin(double xx, double x, double dx) {
        double poly = fma(fma(fma(fma(S5, xx, S4), xx, S3), xx, S2), xx, S1);
        double t = fma(fma(poly, x, -(0.5 * dx)), xx, dx);
        return x + t;
    }

    private static double doCos(double x, double dx) {
        if (x < 0) {
            dx = -dx;
        }
        double ux = BIG + Math.abs(x);
        x = Math.abs(x) - (ux - BIG) + dx;
        double xx = x * x;
        double s = fma(x * xx, fma(xx, SN5, SN3), x);
        double c = xx * fma(xx, fma(xx, CS6, CS4), CS2);
        int k = lo32(ux) << 2;
        double sn = SINCOS[k];
        double ssn = SINCOS[k + 1];
        double cs = SINCOS[k + 2];
        double ccs = SINCOS[k + 3];
        double cor = fma(-sn, s, fma(-cs, c, fma(-s, ssn, ccs)));
        return cs + cor;
    }

    private static double doSin(double x, double dx) {
        double xold = x;
        if (Math.abs(x) < 0.126) {
            return taylorSin(x * x, x, dx);
        }
        if (x <= 0) {
            dx = -dx;
        }
        double ux = BIG + Math.abs(x);
        x = Math.abs(x) - (ux - BIG);
        double xx = x * x;
        double s = x + fma(x * xx, fma(xx, SN5, SN3), dx);
        double c = fma(x, dx, xx * fma(xx, fma(xx, CS6, CS4), CS2));
        int k = lo32(ux) << 2;
        double sn = SINCOS[k];
        double ssn = SINCOS[k + 1];
        double cs = SINCOS[k + 2];
        double ccs = SINCOS[k + 3];
        double cor = fma(cs, s, fma(-sn, c, fma(s, ccs, ssn)));
        return Math.copySign(sn + cor, xold);
    }

    /** reduce_sincos; returns n, writes a and da to out[0], out[1]. */
    private static int reduceSincos(double x, double[] out) {
        double t = fma(x, HPINV, TOINT);
        double xn = t - TOINT;
        double y = fma(-xn, MP2, fma(-xn, MP1, x));
        int n = lo32(t) & 3;
        double t2 = fma(-xn, PP3, y);
        double db = fma(-xn, PP3, y - t2);
        double b = fma(-xn, PP4, t2);
        db += fma(-xn, PP4, t2 - b);
        out[0] = b;
        out[1] = db;
        return n;
    }

    private static double doSincos(double a, double da, int n) {
        double retval = (n & 1) != 0 ? doCos(a, da) : doSin(a, da);
        return (n & 2) != 0 ? -retval : retval;
    }

    static double sin(double x) {
        int k = 0x7fffffff & hi32(x);
        double[] ad = new double[2];
        if (k < 0x3e500000) {
            return x;
        } else if (k < 0x3feb6000) {
            return doSin(x, 0);
        } else if (k < 0x400368fd) {
            double t = HP0 - Math.abs(x);
            return Math.copySign(doCos(t, HP1), x);
        } else if (k < 0x419921FB) {
            int n = reduceSincos(x, ad);
            return doSincos(ad[0], ad[1], n);
        } else if (k < 0x7ff00000) {
            int n = branred(x, ad);
            return doSincos(ad[0], ad[1], n);
        }
        return x / x;
    }

    static double cos(double x) {
        int k = 0x7fffffff & hi32(x);
        double[] ad = new double[2];
        if (k < 0x3e400000) {
            return 1.0;
        } else if (k < 0x3feb6000) {
            return doCos(x, 0);
        } else if (k < 0x400368fd) {
            double y = HP0 - Math.abs(x);
            double a = y + HP1;
            double da = (y - a) + HP1;
            return doSin(a, da);
        } else if (k < 0x419921FB) {
            int n = reduceSincos(x, ad);
            return doSincos(ad[0], ad[1], n + 1);
        } else if (k < 0x7ff00000) {
            int n = branred(x, ad);
            return doSincos(ad[0], ad[1], n + 1);
        }
        return x / x;
    }

    private static final double[] TOVERP = {
        10680707.0, 7228996.0, 1387004.0, 2578385.0, 16069853.0,
        12639074.0, 9804092.0, 4427841.0, 16666979.0, 11263675.0,
        12935607.0, 2387514.0, 4345298.0, 14681673.0, 3074569.0,
        13734428.0, 16653803.0, 1880361.0, 10960616.0, 8533493.0,
        3062596.0, 8710556.0, 7349940.0, 6258241.0, 3772886.0,
        3769171.0, 3798172.0, 8675211.0, 12450088.0, 3874808.0,
        9961438.0, 366607.0, 15675153.0, 9132554.0, 7151469.0,
        3571407.0, 2607881.0, 12013382.0, 4155038.0, 6285869.0,
        7677882.0, 13102053.0, 15825725.0, 473591.0, 9065106.0,
        15363067.0, 6271263.0, 9264392.0, 5636912.0, 4652155.0,
        7056368.0, 13614112.0, 10155062.0, 1944035.0, 9527646.0,
        15080200.0, 6658437.0, 6231200.0, 6832269.0, 16767104.0,
        5075751.0, 3212806.0, 1398474.0, 7579849.0, 6349435.0,
        12618859.0, 4703257.0, 12806093.0, 14477321.0, 2786137.0,
        12875403.0, 9837734.0, 14528324.0, 13719321.0, 343717.0};

    /** One half of __branred: reduces one 27-bit piece; returns {sum, b, bb}. */
    private static double[] branredPart(double xp) {
        final double t576 = 0x1p576;
        final double tm24 = 0x1p-24;
        final double big = 0x1.8p52;
        final double big1 = 0x1.8p54;
        double[] r = new double[6];
        double sum = 0;
        int k = (hi32(xp) >> 20) & 2047;
        k = (k - 450) / 24;
        if (k < 0) {
            k = 0;
        }
        double gor = dbl(bits(t576) - ((long) (k * 24) << 52));
        for (int i = 0; i < 6; i++) {
            r[i] = xp * TOVERP[k + i] * gor;
            gor *= tm24;
        }
        double s;
        for (int i = 0; i < 3; i++) {
            s = (r[i] + big) - big;
            sum += s;
            r[i] -= s;
        }
        double t = 0;
        for (int i = 0; i < 6; i++) {
            t += r[5 - i];
        }
        double bb = (((((r[0] - t) + r[1]) + r[2]) + r[3]) + r[4]) + r[5];
        s = (t + big) - big;
        sum += s;
        t -= s;
        double b = t + bb;
        bb = (t - b) + bb;
        s = (sum + big1) - big1;
        sum -= s;
        return new double[] {sum, b, bb};
    }

    static int branred(double x, double[] out) {
        final double split = 134217729.0;
        x *= 0x1p-600;
        double t = x * split;
        double x1 = t - (t - x);
        double x2 = x - x1;
        double[] p1 = branredPart(x1);
        double[] p2 = branredPart(x2);
        double sum1 = p1[0], b1 = p1[1], bb1 = p1[2];
        double sum2 = p2[0], b2 = p2[1], bb2 = p2[2];
        double sum = sum1 + sum2;
        double b = b1 + b2;
        double bb = (Math.abs(b1) > Math.abs(b2)) ? (b1 - b) + b2 : (b2 - b) + b1;
        if (b > 0.5) {
            b -= 1.0;
            sum += 1.0;
        } else if (b < -0.5) {
            b += 1.0;
            sum -= 1.0;
        }
        double s = b + (bb + bb1 + bb2);
        t = ((b - s) + bb) + (bb1 + bb2);
        b = s * split;
        double t1 = b - (b - s);
        double t2 = s - t1;
        b = s * HP0;
        final double mp1 = 0x1.921FB58000000p0;
        final double mp2 = -0x1.DDE9740000000p-27;
        bb = (((t1 * mp1 - b) + t1 * mp2) + t2 * mp1) + (t2 * mp2 + s * HP1 + t * HP0);
        s = b + bb;
        t = (b - s) + bb;
        out[0] = s;
        out[1] = t;
        return ((int) sum) & 3;
    }

    // ------------------------------------------------------------------------------------------------------------
    // tan (s_tan.c)
    // ------------------------------------------------------------------------------------------------------------

    private static final double TD3 = dbl(0x3FD5555555555555L);
    private static final double TD5 = dbl(0x3FC11111111107C6L);
    private static final double TD7 = dbl(0x3FABA1BA1CDB8745L);
    private static final double TD9 = dbl(0x3F9664ED49CFC666L);
    private static final double TD11 = dbl(0x3F82385A3CF2E4EAL);
    private static final double TE0 = dbl(0x3FD5555555554DBDL);
    private static final double TE1 = dbl(0x3FC11112E0A6B45FL);
    private static final double MFFTNHF = dbl(0xc02f000000000000L);
    private static final double G1 = dbl(0x3e4b096c00000000L);
    private static final double G2 = dbl(0x3faf212d00000000L);
    private static final double G3 = dbl(0x3fe92f1a00000000L);
    private static final double G4 = dbl(0x4039000000000000L);
    private static final double G5 = dbl(0x4197d78400000000L);
    private static final double GY2 = dbl(0x3faf212d00000000L);
    private static final double TMP1 = dbl(0x3FF921FB58000000L);
    private static final double TMP2 = dbl(0xBE4DDE973C000000L);
    private static final double TMP3 = dbl(0xBC8CB3B399D747F2L);
    private static final double TPP3 = dbl(0xBC8CB3B398000000L);
    private static final double TPP4 = dbl(0xbacd747f23e32ed7L);
    private static final double THPINV = dbl(0x3FE45F306DC9C883L);
    private static final double TTOINT = dbl(0x4338000000000000L);

    private static double xfg(int i, int j) {
        return UTAN[4 * i + j];
    }

    /** Polynomial I: da + a^3 * P(a^2), contracted as GCC does. */
    private static double tanPoly(double a, double da) {
        double a2 = a * a;
        double t2 = fma(a2, TD11, TD9);
        t2 = fma(a2, t2, TD7);
        t2 = fma(a2, t2, TD5);
        t2 = fma(a2, t2, TD3);
        return fma(a * a2, t2, da);
    }

    /** The common tail of the reduced-argument branches of __tan. */
    private static double tanReduced(double a, double da, int n) {
        double ya, yya, sy;
        if (a < 0.0) {
            ya = -a;
            yya = -da;
            sy = -1;
        } else {
            ya = a;
            yya = da;
            sy = 1;
        }
        if (ya <= GY2) {
            double t2 = tanPoly(a, da);
            if (n != 0) {
                // EADD (a, t2, b, db); DIV2 (1.0, 0.0, b, db, c, dc, ...)
                double b = a + t2;
                double db = (Math.abs(a) > Math.abs(t2)) ? ((a - b) + t2) : ((t2 - b) + a);
                double c = 1.0 / b;
                double u = c * b;
                double uu = fma(c, b, -u);
                double cc = fma(-c, db, ((1.0 - u) - uu) + 0.0) / b;
                double dc;
                double z = c + cc;
                dc = (c - z) + cc;
                double y = z + dc;
                return -y;
            }
            return a + t2;
        }
        int i = (int) fma(256, ya, MFFTNHF);
        double z = (ya - xfg(i, 0)) + yya;
        double z2 = z * z;
        double pz = fma(z * z2, fma(z2, TE1, TE0), z);
        double fi = xfg(i, 1);
        double gi = xfg(i, 2);
        if (n != 0) {
            double t2 = pz * (fi + gi) / (fi + pz);
            double y = gi - t2;
            return -sy * y;
        }
        double t2 = pz * (gi + fi) / (gi - pz);
        double y = fi + t2;
        return sy * y;
    }

    static double tan(double x) {
        int ux = hi32(x);
        if ((ux & 0x7ff00000) == 0x7ff00000) {
            return x - x;
        }
        double w = (x < 0.0) ? -x : x;
        if (w <= G1) {
            return x;
        }
        if (w <= G2) {
            double x2 = x * x;
            double t2 = fma(x2, TD11, TD9);
            t2 = fma(x2, t2, TD7);
            t2 = fma(x2, t2, TD5);
            t2 = fma(x2, t2, TD3);
            return fma(t2, x * x2, x);
        }
        if (w <= G3) {
            int i = (int) fma(256, w, MFFTNHF);
            double z = w - xfg(i, 0);
            double z2 = z * z;
            double s = (x < 0.0) ? -1 : 1;
            double pz = fma(z * z2, fma(z2, TE1, TE0), z);
            double fi = xfg(i, 1);
            double gi = xfg(i, 2);
            double t2 = pz * (gi + fi) / (gi - pz);
            double y = fi + t2;
            return s * y;
        }
        if (w <= G4) {
            double t = fma(x, THPINV, TTOINT);
            double xn = t - TTOINT;
            double t1 = fma(-xn, TMP2, fma(-xn, TMP1, x));
            int n = lo32(t) & 1;
            double a = fma(-xn, TMP3, t1);
            double da = fma(-xn, TMP3, t1 - a);
            return tanReduced(a, da, n);
        }
        double a;
        double da;
        int n;
        if (w <= G5) {
            double t = fma(x, THPINV, TTOINT);
            double xn = t - TTOINT;
            double t1 = fma(-xn, TMP2, fma(-xn, TMP1, x));
            n = lo32(t) & 1;
            t = fma(-xn, TPP3, t1);
            da = fma(-xn, TPP3, t1 - t);
            a = fma(-xn, TPP4, t);
            da = fma(-xn, TPP4, t - a) + da;
        } else {
            double[] ad = new double[2];
            n = branred(x, ad) & 1;
            a = ad[0];
            da = ad[1];
        }
        // EADD (a, da, t1, t2)
        double s = a + da;
        double ss = (Math.abs(a) > Math.abs(da)) ? ((a - s) + da) : ((da - s) + a);
        return tanReduced(s, ss, n);
    }

    // ------------------------------------------------------------------------------------------------------------
    // atan (s_atan.c), atan2 (e_atan2.c)
    // ------------------------------------------------------------------------------------------------------------

    private static final double AD3 = dbl(0xbfd5555555555555L);
    private static final double AD5 = dbl(0x3fc99999999997fdL);
    private static final double AD7 = dbl(0xbfc24924923f7603L);
    private static final double AD9 = dbl(0x3fbc71c6e5129a3bL);
    private static final double AD11 = dbl(0xbfb7458022b13c25L);
    private static final double AD13 = dbl(0x3fb375f08b31cbceL);
    private static final double ATA = dbl(0x3e4bb67a00000000L);
    private static final double ATB = dbl(0x3fb0000000000000L);
    private static final double ATC = dbl(0x3ff0000000000000L);
    private static final double ATD = dbl(0x4030000000000000L);
    private static final double ATE = dbl(0x43349ff200000000L);
    private static final double HPI = dbl(0x3ff921fb54442d18L);
    private static final double MHPI = dbl(0xbff921fb54442d18L);
    private static final double HPI1 = dbl(0x3c91a62633145c07L);
    private static final double OPI = dbl(0x400921fb54442d18L);
    private static final double OPI1 = dbl(0x3ca1a62633145c07L);
    private static final double MOPI = dbl(0xc00921fb54442d18L);
    private static final double QPI = dbl(0x3fe921fb54442d18L);
    private static final double MQPI = dbl(0xbfe921fb54442d18L);
    private static final double TQPI = dbl(0x4002d97c7f3321d2L);
    private static final double MTQPI = dbl(0xc002d97c7f3321d2L);
    private static final double INV16 = dbl(0x3fb0000000000000L);
    private static final double TWO500 = dbl(0x5f30000000000000L);
    private static final double TWOM500 = dbl(0x20b0000000000000L);
    private static final double TWO52 = 0x1.0p52;

    private static double cij(int i, int j) {
        return UATAN[7 * i + j];
    }

    static double atan(double x) {
        int ux = hi32(x);
        int dx = lo32(x);
        if (((ux & 0x7ff00000) == 0x7ff00000) && (((ux & 0x000fffff) | dx) != 0)) {
            return x + x;
        }
        double u = (x < 0) ? -x : x;
        if (u < ATC) {
            if (u < ATB) {
                if (u < ATA) {
                    return x;
                }
                double v = x * x;
                double yy = fma(v, AD13, AD11);
                yy = fma(v, yy, AD9);
                yy = fma(v, yy, AD7);
                yy = fma(v, yy, AD5);
                yy = fma(v, yy, AD3);
                return fma(yy, x * v, x);
            }
            int i = (int) (fma(256, u, TWO52) - TWO52);
            i -= 16;
            double z = u - cij(i, 0);
            double yy = fma(z, cij(i, 6), cij(i, 5));
            yy = fma(z, yy, cij(i, 4));
            yy = fma(z, yy, cij(i, 3));
            yy = fma(z, yy, cij(i, 2));
            double y = fma(yy, z, cij(i, 1));
            return Math.copySign(y, x);
        }
        if (u < ATD) {
            double w = 1 / u;
            double t1 = w * u;
            double t2 = fma(w, u, -t1);
            int i = (int) (fma(256, w, TWO52) - TWO52);
            i -= 16;
            double z = fma(w, (1 - t1) - t2, w - cij(i, 0));
            double yy = fma(z, cij(i, 6), cij(i, 5));
            yy = fma(z, yy, cij(i, 4));
            yy = fma(z, yy, cij(i, 3));
            yy = fma(z, yy, cij(i, 2));
            yy = fma(-z, yy, HPI1);
            double t1b = HPI - cij(i, 1);
            double y = t1b + yy;
            return Math.copySign(y, x);
        }
        if (u < ATE) {
            double w = 1 / u;
            double v = w * w;
            double t1 = w * u;
            double t2 = fma(w, u, -t1);
            double yy = fma(v, AD13, AD11);
            yy = fma(v, yy, AD9);
            yy = fma(v, yy, AD7);
            yy = fma(v, yy, AD5);
            yy = fma(v, yy, AD3);
            // ESUB (HPI, w, t3, cor)
            double t3 = HPI - w;
            double cor = (Math.abs(HPI) > Math.abs(w)) ? ((HPI - t3) - w) : (HPI - (w + t3));
            double inner = fma(-w, (1 - t1) - t2, HPI1 + cor);
            yy = fma(-yy, w * v, inner);
            double y = t3 + yy;
            return Math.copySign(y, x);
        }
        return x > 0 ? HPI : MHPI;
    }

    private static double atanPolyD(double v) {
        return fma(v, fma(v, fma(v, fma(v, fma(v, AD13, AD11), AD9), AD7), AD5), AD3);
    }

    private static double cijPoly(int i, double v) {
        return fma(v, fma(v, fma(v, cij(i, 6), cij(i, 5)), cij(i, 4)), cij(i, 3));
    }

    static double atan2(double y, double x) {
        int ux = hi32(x);
        int dx = lo32(x);
        if ((ux & 0x7ff00000) == 0x7ff00000) {
            if (((ux & 0x000fffff) | dx) != 0) {
                return x + y;
            }
        }
        int uy = hi32(y);
        int dy = lo32(y);
        if ((uy & 0x7ff00000) == 0x7ff00000) {
            if (((uy & 0x000fffff) | dy) != 0) {
                return y + y;
            }
        }
        if (uy == 0x00000000) {
            if (dy == 0) {
                return (ux & 0x80000000) == 0 ? 0 : OPI;
            }
        } else if (uy == 0x80000000) {
            if (dy == 0) {
                return (ux & 0x80000000) == 0 ? -0.0 : MOPI;
            }
        }
        if (x == 0) {
            return (uy & 0x80000000) == 0 ? HPI : MHPI;
        }
        if (ux == 0x7ff00000) {
            if (dx == 0) {
                if (uy == 0x7ff00000) {
                    if (dy == 0) {
                        return QPI;
                    }
                } else if (uy == 0xfff00000) {
                    if (dy == 0) {
                        return MQPI;
                    }
                } else {
                    return (uy & 0x80000000) == 0 ? 0 : -0.0;
                }
            }
        } else if (ux == 0xfff00000) {
            if (dx == 0) {
                if (uy == 0x7ff00000) {
                    if (dy == 0) {
                        return TQPI;
                    }
                } else if (uy == 0xfff00000) {
                    if (dy == 0) {
                        return MTQPI;
                    }
                } else {
                    return (uy & 0x80000000) == 0 ? OPI : MOPI;
                }
            }
        }
        if (uy == 0x7ff00000) {
            if (dy == 0) {
                return HPI;
            }
        } else if (uy == 0xfff00000) {
            if (dy == 0) {
                return MHPI;
            }
        }
        double ax = (x < 0) ? -x : x;
        double ay = (y < 0) ? -y : y;
        int de = (uy & 0x7ff00000) - (ux & 0x7ff00000);
        if (de >= 59768832) {
            return (y > 0) ? HPI : MHPI;
        } else if (de <= -59768832) {
            if (x > 0) {
                return Math.copySign(ay / ax, y);
            }
            return (y > 0) ? OPI : MOPI;
        }
        if (ax < TWOM500 || ay < TWOM500) {
            ax *= TWO500;
            ay *= TWO500;
        }
        if (ax > TWO500 || ay > TWO500) {
            ax *= TWOM500;
            ay *= TWOM500;
        }
        double u;
        double du;
        double v;
        double vv;
        if (ay < ax) {
            u = ay / ax;
            v = ax * u;
            vv = fma(ax, u, -v);
            du = ((ay - v) - vv) / ax;
        } else {
            u = ax / ay;
            v = ay * u;
            vv = fma(ay, u, -v);
            du = ((ax - v) - vv) / ay;
        }
        double z;
        double zz;
        double t1;
        double t2;
        double t3;
        double cor;
        if (x > 0) {
            if (ay < ax) {
                if (u < INV16) {
                    v = u * u;
                    zz = fma(u * v, atanPolyD(v), du);
                    z = u + zz;
                    return Math.copySign(z, y);
                }
                int i = (int) (fma(256, u, TWO52) - TWO52);
                i -= 16;
                t3 = u - cij(i, 0);
                // EADD (t3, du, v, dv)
                v = t3 + du;
                double dv = (Math.abs(t3) > Math.abs(du)) ? ((t3 - v) + du) : ((du - v) + t3);
                t1 = cij(i, 1);
                t2 = cij(i, 2);
                zz = fma(v, t2, fma(dv, t2, (v * v) * cijPoly(i, v)));
                z = t1 + zz;
                return Math.copySign(z, y);
            }
            if (u < INV16) {
                v = u * u;
                double p = atanPolyD(v);
                // ESUB (hpi, u, t2, cor)
                t2 = HPI - u;
                cor = (Math.abs(HPI) > Math.abs(u)) ? ((HPI - t2) - u) : (HPI - (u + t2));
                t3 = fma(-(u * v), p, (HPI1 + cor) - du);
                z = t2 + t3;
                return Math.copySign(z, y);
            }
            int i = (int) (fma(256, u, TWO52) - TWO52);
            i -= 16;
            v = (u - cij(i, 0)) + du;
            zz = fma(-v, fma(v, cijPoly(i, v), cij(i, 2)), HPI1);
            t1 = HPI - cij(i, 1);
            z = t1 + zz;
            return Math.copySign(z, y);
        }
        if (ax < ay) {
            if (u < INV16) {
                v = u * u;
                double p = atanPolyD(v);
                // EADD (hpi, u, t2, cor)
                t2 = HPI + u;
                cor = (Math.abs(HPI) > Math.abs(u)) ? ((HPI - t2) + u) : ((u - t2) + HPI);
                t3 = fma(u * v, p, (HPI1 + cor) + du);
                z = t2 + t3;
                return Math.copySign(z, y);
            }
            int i = (int) (fma(256, u, TWO52) - TWO52);
            i -= 16;
            v = (u - cij(i, 0)) + du;
            zz = fma(v, fma(v, cijPoly(i, v), cij(i, 2)), HPI1);
            t1 = HPI + cij(i, 1);
            z = t1 + zz;
            return Math.copySign(z, y);
        }
        if (u < INV16) {
            v = u * u;
            double p = atanPolyD(v);
            // ESUB (opi, u, t2, cor)
            t2 = OPI - u;
            cor = (Math.abs(OPI) > Math.abs(u)) ? ((OPI - t2) - u) : (OPI - (u + t2));
            t3 = fma(-(u * v), p, (OPI1 + cor) - du);
            z = t2 + t3;
            return Math.copySign(z, y);
        }
        int i = (int) (fma(256, u, TWO52) - TWO52);
        i -= 16;
        v = (u - cij(i, 0)) + du;
        zz = fma(-v, fma(v, cijPoly(i, v), cij(i, 2)), OPI1);
        t1 = OPI - cij(i, 1);
        z = t1 + zz;
        return Math.copySign(z, y);
    }

    // ------------------------------------------------------------------------------------------------------------
    // asin, acos (e_asin.c)
    // ------------------------------------------------------------------------------------------------------------

    private static final double F1 = 1.66666666666664110590506577996662E-01;
    private static final double F2 = 7.50000000026122686814431784722623E-02;
    private static final double F3 = 4.46428561421059750978517350006940E-02;
    private static final double F4 = 3.03821268582119319911193410625235E-02;
    private static final double F5 = 2.23551211026525610742786300334557E-02;
    private static final double F6 = 1.81382903404565056280372531963613E-02;
    private static final double T24 = 16777216.0;
    private static final double T27 = 134217728.0;
    private static final double RT0 = 9.99999999859990725855365213134618E-01;
    private static final double RT1 = 4.99999999495955425917856814202739E-01;
    private static final double RT2 = 3.75017500867345182581453026130850E-01;
    private static final double RT3 = 3.12523626554518656309172508769531E-01;
    private static final double AHP0 = dbl(0x3FF921FB54442D18L);
    private static final double AHP1 = dbl(0x3C91A62633145C07L);

    private static double fpoly(double z) {
        return fma(fma(fma(fma(fma(F6, z, F5), z, F4), z, F3), z, F2), z, F1);
    }

    /** Table polynomial: xx*xx*(c[n+2] + xx*(... c[n+deg])) + c[n+deg+1], contracted. */
    private static double asnPoly(int n, int last, double xx) {
        double[] c = ASNCS;
        double p = c[last];
        for (int j = last - 1; j >= n + 2; j--) {
            p = fma(xx, p, c[j]);
        }
        return fma(xx * xx, p, c[last + 1]);
    }

    /** Index of the table entry and the degree's last coefficient index for 0.125 <= |x| < 0.96875. */
    private static int[] asnSlot(int k) {
        int n;
        int last;
        if (k < 0x3fe00000) {
            n = k < 0x3fd00000 ? 11 * ((k & 0x000fffff) >> 15) : 11 * ((k & 0x000fffff) >> 14) + 352;
            last = n + 6;
        } else if (k < 0x3fe80000) {
            n = 1056 + ((k & 0x000fe000) >> 11) * 3;
            last = n + 7;
        } else if (k < 0x3fed8000) {
            n = 992 + ((k & 0x000fe000) >> 13) * 13;
            last = n + 8;
        } else if (k < 0x3fee8000) {
            n = 884 + ((k & 0x000fe000) >> 13) * 14;
            last = n + 9;
        } else {
            n = 768 + ((k & 0x000fe000) >> 13) * 15;
            last = n + 10;
        }
        return new int[] {n, last};
    }

    static double asin(double x) {
        int m = hi32(x);
        int k = 0x7fffffff & m;
        if (k < 0x3e500000) {
            return x;
        } else if (k < 0x3fc00000) {
            double x2 = x * x;
            return fma(fpoly(x2), x2 * x, x);
        } else if (k < 0x3fef0000) {
            int[] slot = asnSlot(k);
            int n = slot[0];
            double xx = (m > 0) ? x - ASNCS[n] : -x - ASNCS[n];
            double p = asnPoly(n, slot[1], xx);
            double t = fma(ASNCS[n + 1], xx, p);
            double res = ASNCS[slot[1] + 2] + t;
            return (m > 0) ? res : -res;
        } else if (k < 0x3ff00000) {
            double z = 0.5 * ((m > 0) ? (1.0 - x) : (1.0 + x));
            int kz = hi32(z);
            double t = INROOT[(kz & 0x001fffff) >> 14] * POWTWO[511 - (kz >> 21)];
            double r = fma(-(t * t), z, 1.0);
            t = t * fma(r, fma(r, fma(r, RT3, RT2), RT1), RT0);
            double c = t * z;
            t = c * fma(-(0.5 * t), c, 1.5);
            double y = (c + T24) - T24;
            double cc = fma(-y, y, z) / (t + y);
            double p = fpoly(z) * z;
            double cor = fma(-(2.0 * (y + cc)), p, fma(-2.0, cc, AHP1));
            double res1 = fma(-2.0, y, AHP0);
            double res = res1 + cor;
            return (m > 0) ? res : -res;
        } else if (k == 0x3ff00000 && lo32(x) == 0) {
            return (m > 0) ? AHP0 : -AHP0;
        }
        return (x - x) / (x - x);
    }

    static double acos(double x) {
        int m = hi32(x);
        int k = 0x7fffffff & m;
        if (k < 0x3c880000) {
            return AHP0;
        } else if (k < 0x3fc00000) {
            double x2 = x * x;
            double r = AHP0 - x;
            double cor = fma(-fpoly(x2), x2 * x, (((AHP0 - r) - x) + AHP1));
            return r + cor;
        } else if (k < 0x3fef0000) {
            int[] slot = asnSlot(k);
            int n = slot[0];
            double xx = (m > 0) ? x - ASNCS[n] : -x - ASNCS[n];
            double p = asnPoly(n, slot[1], xx);
            double t = fma(ASNCS[n + 1], xx, p);
            double c = ASNCS[slot[1] + 2];
            double y = (m > 0) ? (AHP0 - c) : (AHP0 + c);
            t = (m > 0) ? (AHP1 - t) : (AHP1 + t);
            return y + t;
        } else if (k < 0x3ff00000) {
            double z = 0.5 * ((m > 0) ? (1.0 - x) : (1.0 + x));
            int kz = hi32(z);
            double t = INROOT[(kz & 0x001fffff) >> 14] * POWTWO[511 - (kz >> 21)];
            double r = fma(-(t * t), z, 1.0);
            t = t * fma(r, fma(r, fma(r, RT3, RT2), RT1), RT0);
            double c = t * z;
            t = c * fma(-(0.5 * t), c, 1.5);
            double y = (T27 * c + c) - T27 * c;
            double cc = fma(-y, y, z) / (t + y);
            double p = fpoly(z) * z;
            // (y + cc) * p is common to both branches: GCC hoists it above the branch, so it is not contracted.
            double yccp = (y + cc) * p;
            if (m < 0) {
                double cor = (AHP1 - cc) - yccp;
                double res1 = AHP0 - y;
                double res = res1 + cor;
                return res + res;
            }
            double cor = cc + yccp;
            double res = y + cor;
            return res + res;
        } else if (k == 0x3ff00000 && lo32(x) == 0) {
            return (m > 0) ? 0 : 2.0 * AHP0;
        }
        return (x - x) / (x - x);
    }

    // ------------------------------------------------------------------------------------------------------------
    // expm1 (s_expm1.c, FMA variant), sinh, cosh, tanh (fdlibm, no FMA variant)
    // ------------------------------------------------------------------------------------------------------------

    static double expm1(double x) {
        final double huge = 1.0e+300;
        final double tiny = 1.0e-300;
        final double oThreshold = 7.09782712893383973096e+02;
        final double ln2Hi = 6.93147180369123816490e-01;
        final double ln2Lo = 1.90821492927058770002e-10;
        final double invln2 = 1.44269504088896338700e+00;
        final double q1 = -3.33333333333331316428e-02;
        final double q2 = 1.58730158725481460165e-03;
        final double q3 = -7.93650757867487942473e-05;
        final double q4 = 4.00821782732936239552e-06;
        final double q5 = -2.01099218183624371326e-07;
        double y;
        double hi;
        double lo;
        double c = 0;
        double t;
        int k;
        int hx = hi32(x);
        int xsb = hx & 0x80000000;
        hx &= 0x7fffffff;
        if (hx >= 0x4043687A) {
            if (hx >= 0x40862E42) {
                if (hx >= 0x7ff00000) {
                    if (((hx & 0xfffff) | lo32(x)) != 0) {
                        return x + x;
                    }
                    return (xsb == 0) ? x : -1.0;
                }
                if (x > oThreshold) {
                    return huge * huge;
                }
            }
            if (xsb != 0) {
                return tiny - 1.0;
            }
        }
        if (hx > 0x3fd62e42) {
            if (hx < 0x3FF0A2B2) {
                if (xsb == 0) {
                    hi = x - ln2Hi;
                    lo = ln2Lo;
                    k = 1;
                } else {
                    hi = x + ln2Hi;
                    lo = -ln2Lo;
                    k = -1;
                }
                x = hi - lo;
                c = (hi - x) - lo;
            } else {
                k = (int) fma(invln2, x, (xsb == 0) ? 0.5 : -0.5);
                t = k;
                hi = fma(-t, ln2Hi, x);
                x = fma(-t, ln2Lo, hi);
                c = fma(-t, ln2Lo, hi - x);
            }
        } else if (hx < 0x3c900000) {
            t = huge + x;
            return x - (t - (huge + x));
        } else {
            k = 0;
        }
        double hfx = 0.5 * x;
        double hxs = x * hfx;
        double r1a = fma(hxs, q1, 1.0);
        double h2 = hxs * hxs;
        double r2a = fma(hxs, q3, q2);
        double h4 = h2 * h2;
        double r3a = fma(hxs, q5, q4);
        double r1 = fma(h4, r3a, fma(h2, r2a, r1a));
        t = fma(-r1, hfx, 3.0);
        double e = hxs * ((r1 - t) / fma(-x, t, 6.0));
        if (k == 0) {
            return x - fma(x, e, -hxs);
        }
        e = fma(x, e - c, -c);
        e -= hxs;
        if (k == -1) {
            return fma(0.5, x - e, -0.5);
        }
        if (k == 1) {
            if (x < -0.25) {
                return -2.0 * (e - (x + 0.5));
            }
            return fma(2.0, x - e, 1.0);
        }
        if (k <= -2 || k > 56) {
            y = 1.0 - (e - x);
            y = withHigh(y, hi32(y) + (k << 20));
            return y - 1.0;
        }
        t = 1.0;
        if (k < 20) {
            t = withHigh(t, 0x3ff00000 - (0x200000 >> k));
            y = t - (e - x);
            y = withHigh(y, hi32(y) + (k << 20));
        } else {
            t = withHigh(t, (0x3ff - k) << 20);
            y = x - (e + t);
            y += 1.0;
            y = withHigh(y, hi32(y) + (k << 20));
        }
        return y;
    }

    private static double withHigh(double d, int high) {
        return dbl(((long) high << 32) | (bits(d) & 0xffffffffL));
    }

    static double sinh(double x) {
        final double shuge = 1.0e307;
        int jx = hi32(x);
        int ix = jx & 0x7fffffff;
        if (ix >= 0x7ff00000) {
            return x + x;
        }
        double h = 0.5;
        if (jx < 0) {
            h = -h;
        }
        if (ix < 0x40360000) {
            if (ix < 0x3e300000) {
                if (shuge + x > 1.0) {
                    return x;
                }
            }
            double t = expm1(Math.abs(x));
            if (ix < 0x3ff00000) {
                return h * (2.0 * t - t * t / (t + 1.0));
            }
            return h * (t + t / (t + 1.0));
        }
        if (ix < 0x40862e42) {
            return h * exp(Math.abs(x));
        }
        int lx = lo32(x);
        if (ix < 0x408633ce || ((ix == 0x408633ce) && Integer.compareUnsigned(lx, 0x8fb9f87d) <= 0)) {
            double w = exp(0.5 * Math.abs(x));
            double t = h * w;
            return t * w;
        }
        return x * shuge;
    }

    static double cosh(double x) {
        int ix = hi32(x) & 0x7fffffff;
        if (ix < 0x40360000) {
            if (ix < 0x3fd62e43) {
                if (ix < 0x3c800000) {
                    return 1.0;
                }
                double t = expm1(Math.abs(x));
                double w = 1.0 + t;
                return 1.0 + (t * t) / (w + w);
            }
            double t = exp(Math.abs(x));
            return 0.5 * t + 0.5 / t;
        }
        if (ix < 0x40862e42) {
            return 0.5 * exp(Math.abs(x));
        }
        long fix = bits(x) & 0x7fffffffffffffffL;
        if (fix <= 0x408633ce8fb9f87dL) {
            double w = exp(0.5 * Math.abs(x));
            double t = 0.5 * w;
            return t * w;
        }
        if (ix >= 0x7ff00000) {
            return x * x;
        }
        return Double.POSITIVE_INFINITY;
    }

    static double tanh(double x) {
        int jx = hi32(x);
        int lx = lo32(x);
        int ix = jx & 0x7fffffff;
        if (ix >= 0x7ff00000) {
            return jx >= 0 ? 1.0 / x + 1.0 : 1.0 / x - 1.0;
        }
        double z;
        if (ix < 0x40360000) {
            if ((ix | lx) == 0) {
                return x;
            }
            if (ix < 0x3c800000) {
                return x * (1.0 + x);
            }
            if (ix >= 0x3ff00000) {
                double t = expm1(2.0 * Math.abs(x));
                z = 1.0 - 2.0 / (t + 2.0);
            } else {
                double t = expm1(-2.0 * Math.abs(x));
                z = -t / (t + 2.0);
            }
        } else {
            z = 1.0 - 1.0e-300;
        }
        return jx >= 0 ? z : -z;
    }
}
