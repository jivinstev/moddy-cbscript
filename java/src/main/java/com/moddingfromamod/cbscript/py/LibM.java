// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

/**
 * The C library math functions Python's {@code math} module and float {@code **} call.
 *
 * <p>CPython calls the platform's libm, which is not correctly rounded and differs between platforms. Here:
 * <ul>
 *   <li>sin, cos, tan, asin, acos, atan, atan2, exp, log, log2 and pow are {@link CRMath}: correctly rounded, so
 *       equal to CPython's result wherever its libm rounds correctly (nearly always);</li>
 *   <li>sinh, cosh and tanh are fdlibm's formulas ({@link FdHyp}) over that exp, as most C libraries compute them;</li>
 *   <li>expm1 and log10 are {@link java.lang.StrictMath} (fdlibm, the algorithms most C libraries use for them).</li>
 * </ul>
 * Special values (infinities, NaN, zeros, out-of-domain arguments) go to StrictMath, whose IEEE results are the C
 * standard's. See JAVA_PORT.md, "Compile-time math", for what this means for parity.
 */
final class LibM {
    private LibM() {
    }

    private static boolean finite(double x) {
        return !Double.isNaN(x) && !Double.isInfinite(x);
    }

    static double exp(double x) {
        return finite(x) ? CRMath.exp(x) : StrictMath.exp(x);
    }

    static double expm1(double x) {
        return StrictMath.expm1(x);
    }

    static double log(double x) {
        return finite(x) && x > 0 ? CRMath.log(x) : StrictMath.log(x);
    }

    static double log10(double x) {
        return StrictMath.log10(x);
    }

    static double log2(double x) {
        return finite(x) && x > 0 ? CRMath.log2(x) : StrictMath.log(x);
    }

    static double pow(double x, double y) {
        if (y == 0 || x == 1) {
            return 1.0;
        }
        if (!finite(x) || !finite(y) || x == 0) {
            return StrictMath.pow(x, y);
        }
        if (x < 0) {
            if (y != Math.rint(y)) {
                return Double.NaN;
            }
            double r = CRMath.pow(-x, y);
            boolean odd = Math.abs(y) < 0x1p53 && ((long) Math.abs(y)) % 2 == 1;
            return odd ? -r : r;
        }
        return CRMath.pow(x, y);
    }

    static double sin(double x) {
        return finite(x) ? CRMath.sin(x) : StrictMath.sin(x);
    }

    static double cos(double x) {
        return finite(x) ? CRMath.cos(x) : StrictMath.cos(x);
    }

    static double tan(double x) {
        return finite(x) ? CRMath.tan(x) : StrictMath.tan(x);
    }

    static double asin(double x) {
        return finite(x) && Math.abs(x) <= 1 ? CRMath.asin(x) : StrictMath.asin(x);
    }

    static double acos(double x) {
        return finite(x) && Math.abs(x) <= 1 ? CRMath.acos(x) : StrictMath.acos(x);
    }

    static double atan(double x) {
        return finite(x) ? CRMath.atan(x) : StrictMath.atan(x);
    }

    static double atan2(double y, double x) {
        return finite(x) && finite(y) && !(x == 0 && y == 0) ? CRMath.atan2(y, x) : StrictMath.atan2(y, x);
    }

    static double sinh(double x) {
        return finite(x) ? FdHyp.sinh(x) : StrictMath.sinh(x);
    }

    static double cosh(double x) {
        return finite(x) ? FdHyp.cosh(x) : StrictMath.cosh(x);
    }

    static double tanh(double x) {
        return finite(x) ? FdHyp.tanh(x) : StrictMath.tanh(x);
    }
}
