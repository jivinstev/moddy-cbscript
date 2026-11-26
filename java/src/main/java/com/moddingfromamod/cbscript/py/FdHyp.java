// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

/**
 * sinh, cosh and tanh by fdlibm's formulas (e_sinh.c, e_cosh.c, s_tanh.c), written for this project.
 *
 * <p>Most C libraries, glibc among them, compute these three with exactly these formulas over their own {@code exp}
 * and {@code expm1}. The formulas are not correctly rounded, so reproducing them (over a correctly rounded exp and
 * StrictMath's expm1) agrees with CPython far more often than a correctly rounded sinh would. The formulas are from
 * fdlibm, whose notice reads: "Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved. Developed at SunSoft,
 * a Sun Microsystems, Inc. business. Permission to use, copy, modify, and distribute this software is freely granted,
 * provided that this notice is preserved."
 */
final class FdHyp {
    private FdHyp() {
    }

    private static final double LN_MAX = 7.09782712893383973096e+02;
    private static final double OVERFLOW = 7.10475860073943863426e+02;

    private static double exp(double x) {
        return CRMath.exp(x);
    }

    static double sinh(double x) {
        double h = x < 0 ? -0.5 : 0.5;
        double ax = Math.abs(x);
        if (ax < 22) {
            if (ax < 0x1p-28) {
                return x;
            }
            double t = StrictMath.expm1(ax);
            if (ax < 1) {
                return h * (2.0 * t - t * t / (t + 1.0));
            }
            return h * (t + t / (t + 1.0));
        }
        if (ax < LN_MAX) {
            return h * exp(ax);
        }
        if (ax <= OVERFLOW) {
            double w = exp(0.5 * ax);
            double t = h * w;
            return t * w;
        }
        return x * 1.0e307;
    }

    static double cosh(double x) {
        double ax = Math.abs(x);
        if (ax < 0.5 * 0.6931471805599453) {
            if (ax < 0x1p-55) {
                return 1.0;
            }
            double t = StrictMath.expm1(ax);
            double w = 1.0 + t;
            return 1.0 + (t * t) / (w + w);
        }
        if (ax < 22) {
            double t = exp(ax);
            return 0.5 * t + 0.5 / t;
        }
        if (ax < LN_MAX) {
            return 0.5 * exp(ax);
        }
        if (ax <= OVERFLOW) {
            double w = exp(0.5 * ax);
            double t = 0.5 * w;
            return t * w;
        }
        return 1.0e307 * 1.0e307;
    }

    static double tanh(double x) {
        double ax = Math.abs(x);
        double z;
        if (ax < 22) {
            if (ax < 0x1p-55) {
                return x * (1.0 + x);
            }
            if (ax >= 1) {
                double t = StrictMath.expm1(2.0 * ax);
                z = 1.0 - 2.0 / (t + 2.0);
            } else {
                double t = StrictMath.expm1(-2.0 * ax);
                z = -t / (t + 2.0);
            }
        } else {
            z = 1.0 - 1.0e-300;
        }
        return x >= 0 ? z : -z;
    }
}
