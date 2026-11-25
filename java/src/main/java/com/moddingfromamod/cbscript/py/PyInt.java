// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.math.BigInteger;

/**
 * Python's {@code int}: arbitrary precision, held in a {@code long} when it fits and a {@link BigInteger} otherwise.
 * Small values (-5..256) are cached, as in CPython, so {@code is} behaves the same on them.
 */
public class PyInt extends PyObject {
    private static final PyInt[] CACHE = new PyInt[262];
    private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
    private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);
    /** CPython's limit on decimal conversions of large ints (sys.get_int_max_str_digits()). */
    static final int MAX_STR_DIGITS = 4300;

    static {
        for (int i = 0; i < CACHE.length; i++) {
            CACHE[i] = new PyInt(i - 5);
        }
    }

    private final long small;
    /** Non-null exactly when the value does not fit in a long. */
    private final BigInteger big;

    PyInt(long v) {
        this.small = v;
        this.big = null;
    }

    private PyInt(BigInteger v) {
        this.small = 0;
        this.big = v;
    }

    static PyInt of(long v) {
        if (v >= -5 && v <= 256) {
            return CACHE[(int) v + 5];
        }
        return new PyInt(v);
    }

    static PyInt of(BigInteger v) {
        if (v.compareTo(LONG_MIN) >= 0 && v.compareTo(LONG_MAX) <= 0) {
            return of(v.longValue());
        }
        return new PyInt(v);
    }

    /** The value. */
    public BigInteger value() {
        return big != null ? big : BigInteger.valueOf(small);
    }

    /** True if the value fits in a Java long. */
    public boolean isLong() {
        return big == null;
    }

    /** The value as a long; only meaningful when {@link #isLong()}. */
    public long longValue() {
        return small;
    }

    /** The value as a Java int, or the given OverflowError/IndexError-style exception when it does not fit. */
    public int intValueExact() {
        if (big == null && small >= Integer.MIN_VALUE && small <= Integer.MAX_VALUE) {
            return (int) small;
        }
        throw new PyException("OverflowError", "Python int too large to convert to C int");
    }

    /** The value as a long for an index (Py_ssize_t), raising CPython's OverflowError if it does not fit. */
    public long asIndex() {
        if (big != null) {
            throw new PyException("IndexError", "cannot fit 'int' into an index-sized integer");
        }
        return small;
    }

    public int signum() {
        return big != null ? big.signum() : Long.signum(small);
    }

    /** {@code float(x)}, correctly rounded; OverflowError if out of range. */
    public double toDouble() {
        if (big == null) {
            return (double) small;
        }
        double d = big.doubleValue();
        if (Double.isInfinite(d)) {
            throw new PyException("OverflowError", "int too large to convert to float");
        }
        return d;
    }

    @Override
    public String typeName() {
        return "int";
    }

    @Override
    public String repr() {
        if (big == null) {
            return Long.toString(small);
        }
        if (big.bitLength() > 14000) {
            // Above 2**14286 there are certainly more than 4300 digits; near the limit, count them.
            if (big.bitLength() > 14286 || big.abs().toString().length() > MAX_STR_DIGITS) {
                throw PyException.valueError("Exceeds the limit (4300 digits) for integer string conversion; "
                        + "use sys.set_int_max_str_digits() to increase the limit");
            }
        }
        return big.toString();
    }

    @Override
    public boolean truthy() {
        return signum() != 0;
    }

    @Override
    public long hash() {
        return Py.hashInt(this);
    }
}
