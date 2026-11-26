// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.DoubleUnaryOperator;

/**
 * Python's {@code math} module (CPython 3.13 {@code Modules/mathmodule.c}): the libm functions come from
 * {@link LibM} (StrictMath; may differ from a given CPython in the last bit); CPython's own wrappers (error rules, {@code hypot}, {@code log} of huge ints,
 * {@code pow} special cases) are ported here.
 */
final class PyMath {
    private PyMath() {}

    static final PyModule MODULE = build();

    private static void def(PyModule m, String name, BiFunction<List<PyObject>, Map<String, PyObject>, PyObject> f) {
        m.attrs.put(name, new PyFunction(name, f));
    }

    /** math_1: one-argument libm function with CPython's error rules. */
    private static void func1(PyModule m, String name, DoubleUnaryOperator f, boolean canOverflow) {
        def(m, name, (a, kw) -> {
            double x = Py.asDouble(Args.one("math." + name, a, kw));
            return Py.of(math1(x, f, canOverflow));
        });
    }

    private static double math1(double x, DoubleUnaryOperator f, boolean canOverflow) {
        double r = f.applyAsDouble(x);
        if (Double.isNaN(r) && !Double.isNaN(x)) {
            throw domain();
        }
        if (Double.isInfinite(r) && Double.isFinite(x)) {
            if (canOverflow) {
                throw range();
            }
            throw domain();
        }
        return r;
    }

    private static PyException domain() {
        return PyException.valueError("math domain error");
    }

    private static PyException range() {
        return new PyException("OverflowError", "math range error");
    }

    /** math_2 for a two-argument function. */
    private static double math2(double x, double y, double r) {
        if (Double.isNaN(r) && !Double.isNaN(x) && !Double.isNaN(y)) {
            throw domain();
        }
        if (Double.isInfinite(r) && Double.isFinite(x) && Double.isFinite(y)) {
            throw range();
        }
        return r;
    }

    private static List<PyObject> two(String name, List<PyObject> a, Map<String, PyObject> kw) {
        Args.noKwargs("math." + name, kw);
        if (a.size() != 2) {
            throw PyException.typeError(name + " expected 2 arguments, got " + a.size());
        }
        return a;
    }

    private static PyModule build() {
        PyModule m = new PyModule("math", "<module 'math' (built-in)>");
        m.attrs.put("pi", Py.of(Math.PI));
        m.attrs.put("e", Py.of(Math.E));
        m.attrs.put("tau", Py.of(2 * Math.PI));
        m.attrs.put("inf", Py.of(Double.POSITIVE_INFINITY));
        m.attrs.put("nan", Py.of(Double.NaN));
        func1(m, "acos", LibM::acos, false);
        func1(m, "asin", LibM::asin, false);
        func1(m, "atan", LibM::atan, false);
        func1(m, "cos", LibM::cos, false);
        func1(m, "sin", LibM::sin, false);
        func1(m, "tan", LibM::tan, false);
        func1(m, "cosh", LibM::cosh, true);
        func1(m, "sinh", LibM::sinh, true);
        func1(m, "tanh", LibM::tanh, false);
        func1(m, "exp", LibM::exp, true);
        func1(m, "expm1", LibM::expm1, true);
        func1(m, "fabs", Math::abs, false);
        func1(m, "sqrt", Math::sqrt, false);
        def(m, "atan2", (a, kw) -> {
            two("atan2", a, kw);
            double y = Py.asDouble(a.get(0));
            double x = Py.asDouble(a.get(1));
            return Py.of(math2(y, x, atan2(y, x)));
        });
        def(m, "copysign", (a, kw) -> {
            two("copysign", a, kw);
            double x = Py.asDouble(a.get(0));
            double y = Py.asDouble(a.get(1));
            return Py.of(Math.copySign(x, y));
        });
        def(m, "fmod", (a, kw) -> {
            two("fmod", a, kw);
            double x = Py.asDouble(a.get(0));
            double y = Py.asDouble(a.get(1));
            if (Double.isInfinite(y) && Double.isFinite(x)) {
                return Py.of(x);
            }
            double r = x % y;
            if (Double.isNaN(r) && !Double.isNaN(x) && !Double.isNaN(y)) {
                throw domain();
            }
            return Py.of(r);
        });
        def(m, "pow", (a, kw) -> {
            two("pow", a, kw);
            return Py.of(mathPow(Py.asDouble(a.get(0)), Py.asDouble(a.get(1))));
        });
        def(m, "degrees", (a, kw) -> Py.of(Py.asDouble(Args.one("math.degrees", a, kw)) * (180.0 / Math.PI)));
        def(m, "radians", (a, kw) -> Py.of(Py.asDouble(Args.one("math.radians", a, kw)) * (Math.PI / 180.0)));
        def(m, "ceil", (a, kw) -> {
            PyObject x = Args.one("math.ceil", a, kw);
            if (x instanceof PyInt i) {
                return PyInt.of(i.value());
            }
            return Py.floatToInt(Math.ceil(Py.asDouble(x)));
        });
        def(m, "floor", (a, kw) -> {
            PyObject x = Args.one("math.floor", a, kw);
            if (x instanceof PyInt i) {
                return PyInt.of(i.value());
            }
            return Py.floatToInt(Math.floor(Py.asDouble(x)));
        });
        def(m, "trunc", (a, kw) -> {
            PyObject x = Args.one("math.trunc", a, kw);
            if (x instanceof PyInt i) {
                return PyInt.of(i.value());
            }
            if (x instanceof PyFloat f) {
                return Py.floatToInt(f.value());
            }
            throw PyException.typeError("type " + x.typeName() + " doesn't define __trunc__ method");
        });
        def(m, "isfinite", (a, kw) -> Py.of(Double.isFinite(Py.asDouble(Args.one("math.isfinite", a, kw)))));
        def(m, "isinf", (a, kw) -> Py.of(Double.isInfinite(Py.asDouble(Args.one("math.isinf", a, kw)))));
        def(m, "isnan", (a, kw) -> Py.of(Double.isNaN(Py.asDouble(Args.one("math.isnan", a, kw)))));
        def(m, "log", (a, kw) -> {
            Args.noKwargs("math.log", kw);
            Args.count("log", a, 1, 2);
            double num = logHelper(a.get(0), PyMath::mLog);
            if (a.size() == 1) {
                return Py.of(num);
            }
            double den = logHelper(a.get(1), PyMath::mLog);
            if (den == 0) {
                throw new PyException("ZeroDivisionError", "float division by zero");
            }
            return Py.of(num / den);
        });
        def(m, "log2", (a, kw) -> Py.of(logHelper(Args.one("math.log2", a, kw), PyMath::mLog2)));
        def(m, "log10", (a, kw) -> Py.of(logHelper(Args.one("math.log10", a, kw), PyMath::mLog10)));
        def(m, "hypot", (a, kw) -> {
            Args.noKwargs("math.hypot", kw);
            double[] v = new double[a.size()];
            double max = 0;
            boolean nan = false;
            for (int i = 0; i < v.length; i++) {
                v[i] = Math.abs(Py.asDouble(a.get(i)));
                nan |= Double.isNaN(v[i]);
                if (v[i] > max) {
                    max = v[i];
                }
            }
            return Py.of(vectorNorm(v, max, nan));
        });
        def(m, "dist", (a, kw) -> {
            two("dist", a, kw);
            List<PyObject> p = Py.toList(a.get(0));
            List<PyObject> q = Py.toList(a.get(1));
            if (p.size() != q.size()) {
                throw PyException.valueError("both points must have the same number of dimensions");
            }
            double[] v = new double[p.size()];
            double max = 0;
            boolean nan = false;
            for (int i = 0; i < v.length; i++) {
                v[i] = Math.abs(Py.asDouble(p.get(i)) - Py.asDouble(q.get(i)));
                nan |= Double.isNaN(v[i]);
                if (v[i] > max) {
                    max = v[i];
                }
            }
            return Py.of(vectorNorm(v, max, nan));
        });
        def(m, "isqrt", (a, kw) -> {
            BigInteger n = Py.index(Args.one("math.isqrt", a, kw)).value();
            if (n.signum() < 0) {
                throw PyException.valueError("isqrt() argument must be nonnegative");
            }
            return PyInt.of(n.sqrt());
        });
        def(m, "gcd", (a, kw) -> {
            Args.noKwargs("math.gcd", kw);
            BigInteger g = BigInteger.ZERO;
            for (PyObject o : a) {
                g = g.gcd(Py.index(o).value());
            }
            return PyInt.of(g);
        });
        def(m, "lcm", (a, kw) -> {
            Args.noKwargs("math.lcm", kw);
            BigInteger l = BigInteger.ONE;
            for (PyObject o : a) {
                BigInteger v = Py.index(o).value().abs();
                if (v.signum() == 0) {
                    l = BigInteger.ZERO;
                } else if (l.signum() != 0) {
                    l = l.divide(l.gcd(v)).multiply(v);
                }
            }
            return PyInt.of(l);
        });
        def(m, "factorial", (a, kw) -> {
            PyObject x = Args.one("math.factorial", a, kw);
            PyInt n = Py.index(x);
            if (n.signum() < 0) {
                throw PyException.valueError("factorial() not defined for negative values");
            }
            if (!n.isLong() || n.longValue() > 100000) {
                throw new PyException("OverflowError", "factorial() argument should not exceed "
                        + Long.MAX_VALUE);
            }
            BigInteger r = BigInteger.ONE;
            for (long i = 2; i <= n.longValue(); i++) {
                r = r.multiply(BigInteger.valueOf(i));
            }
            return PyInt.of(r);
        });
        def(m, "comb", (a, kw) -> {
            two("comb", a, kw);
            BigInteger n = Py.index(a.get(0)).value();
            BigInteger k = Py.index(a.get(1)).value();
            if (n.signum() < 0) {
                throw PyException.valueError("n must be a non-negative integer");
            }
            if (k.signum() < 0) {
                throw PyException.valueError("k must be a non-negative integer");
            }
            return PyInt.of(comb(n, k));
        });
        def(m, "perm", (a, kw) -> {
            Args.noKwargs("math.perm", kw);
            Args.count("perm", a, 1, 2);
            BigInteger n = Py.index(a.get(0)).value();
            BigInteger k = a.size() > 1 && a.get(1) != Py.NONE ? Py.index(a.get(1)).value() : n;
            if (n.signum() < 0) {
                throw PyException.valueError("n must be a non-negative integer");
            }
            if (k.signum() < 0) {
                throw PyException.valueError("k must be a non-negative integer");
            }
            if (k.compareTo(n) > 0) {
                return PyInt.of(0);
            }
            BigInteger r = BigInteger.ONE;
            for (BigInteger i = n.subtract(k).add(BigInteger.ONE); i.compareTo(n) <= 0; i = i.add(BigInteger.ONE)) {
                r = r.multiply(i);
            }
            return PyInt.of(r);
        });
        def(m, "prod", (a, kw) -> {
            Args.onlyKw("prod", kw, "start");
            Args.count("prod", a, 1, 1);
            PyObject r = kw != null && kw.containsKey("start") ? kw.get("start") : PyInt.of(1);
            for (PyObject o : Py.toList(a.get(0))) {
                r = Py.binop("*", r, o);
            }
            return r;
        });
        def(m, "ldexp", (a, kw) -> {
            two("ldexp", a, kw);
            double x = Py.asDouble(a.get(0));
            PyInt e = Py.index(a.get(1));
            long exp = e.isLong() ? e.longValue() : (e.signum() < 0 ? Integer.MIN_VALUE : Integer.MAX_VALUE);
            if (x == 0 || !Double.isFinite(x)) {
                return Py.of(x);
            }
            double r = Math.scalb(x, (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, exp)));
            if (Double.isInfinite(r)) {
                throw range();
            }
            return Py.of(r);
        });
        def(m, "frexp", (a, kw) -> {
            double x = Py.asDouble(Args.one("math.frexp", a, kw));
            if (x == 0 || !Double.isFinite(x)) {
                return Py.tuple(Py.of(x), PyInt.of(0));
            }
            int e = Math.getExponent(x) == Double.MIN_EXPONENT - 1 ? Math.getExponent(x * 0x1p54) + 1 - 54
                    : Math.getExponent(x) + 1;
            return Py.tuple(Py.of(Math.scalb(x, -e)), PyInt.of(e));
        });
        def(m, "modf", (a, kw) -> {
            double x = Py.asDouble(Args.one("math.modf", a, kw));
            if (Double.isInfinite(x)) {
                return Py.tuple(Py.of(Math.copySign(0.0, x)), Py.of(x));
            }
            double ip = x < 0 ? Math.ceil(x) : Math.floor(x);
            return Py.tuple(Py.of(Math.copySign(x - ip, x)), Py.of(ip));
        });
        def(m, "isclose", (a, kw) -> {
            Args.onlyKw("isclose", kw, "rel_tol", "abs_tol");
            if (a.size() != 2) {
                throw PyException.typeError("isclose() takes exactly 2 positional arguments (" + a.size()
                        + " given)");
            }
            double x = Py.asDouble(a.get(0));
            double y = Py.asDouble(a.get(1));
            double rel = kw != null && kw.containsKey("rel_tol") ? Py.asDouble(kw.get("rel_tol")) : 1e-09;
            double abs = kw != null && kw.containsKey("abs_tol") ? Py.asDouble(kw.get("abs_tol")) : 0.0;
            if (rel < 0.0 || abs < 0.0) {
                throw PyException.valueError("tolerances must be non-negative");
            }
            if (x == y) {
                return Py.TRUE;
            }
            if (Double.isInfinite(x) || Double.isInfinite(y)) {
                return Py.FALSE;
            }
            double diff = Math.abs(y - x);
            return Py.of(((diff <= Math.abs(rel * y)) || (diff <= Math.abs(rel * x))) || (diff <= abs));
        });
        def(m, "fsum", (a, kw) -> Py.of(fsum(Py.toList(Args.one("math.fsum", a, kw)))));
        return m;
    }

    private static BigInteger comb(BigInteger n, BigInteger k) {
        if (k.compareTo(n) > 0) {
            return BigInteger.ZERO;
        }
        BigInteger kk = k.min(n.subtract(k));
        BigInteger r = BigInteger.ONE;
        for (BigInteger i = BigInteger.ONE; i.compareTo(kk) <= 0; i = i.add(BigInteger.ONE)) {
            r = r.multiply(n.subtract(kk).add(i)).divide(i);
        }
        return r;
    }

    /** CPython's m_atan2 special cases, then libm atan2. */
    static double atan2(double y, double x) {
        if (Double.isNaN(x) || Double.isNaN(y)) {
            return Double.NaN;
        }
        if (Double.isInfinite(y)) {
            if (Double.isInfinite(x)) {
                if (Math.copySign(1., x) == 1.) {
                    return Math.copySign(0.25 * Math.PI, y);
                }
                return Math.copySign(0.75 * Math.PI, y);
            }
            return Math.copySign(0.5 * Math.PI, y);
        }
        if (Double.isInfinite(x) || y == 0.) {
            if (Math.copySign(1., x) == 1.) {
                return Math.copySign(0., y);
            }
            return Math.copySign(Math.PI, y);
        }
        return LibM.atan2(y, x);
    }

    /** m_log: EDOM cases become NaN/-inf, which math_1's rules turn into ValueError. */
    private static double mLog(double x) {
        if (Double.isFinite(x)) {
            if (x > 0.0) {
                return LibM.log(x);
            }
            return x == 0.0 ? Double.NEGATIVE_INFINITY : Double.NaN;
        }
        if (Double.isNaN(x) || x > 0.0) {
            return x;
        }
        return Double.NaN;
    }

    private static double mLog2(double x) {
        if (!Double.isFinite(x)) {
            if (Double.isNaN(x) || x > 0.0) {
                return x;
            }
            return Double.NaN;
        }
        if (x > 0.0) {
            return LibM.log2(x);
        }
        return x == 0.0 ? Double.NEGATIVE_INFINITY : Double.NaN;
    }

    private static double mLog10(double x) {
        if (Double.isFinite(x)) {
            if (x > 0.0) {
                return LibM.log10(x);
            }
            return x == 0.0 ? Double.NEGATIVE_INFINITY : Double.NaN;
        }
        if (Double.isNaN(x) || x > 0.0) {
            return x;
        }
        return Double.NaN;
    }

    /** loghelper: ints are handled exactly (also beyond the float range); floats go through math_1. */
    private static double logHelper(PyObject arg, DoubleUnaryOperator func) {
        if (arg instanceof PyInt i) {
            if (i.signum() <= 0) {
                throw domain();
            }
            BigInteger v = i.value();
            double x = v.doubleValue();
            if (Double.isInfinite(x)) {
                // _PyLong_Frexp: v ~= x * 2**e with 0.5 <= x < 1, x correctly rounded to 53 bits
                long e = v.bitLength();
                double frac = Py.intTrueDivide(v, BigInteger.ONE.shiftLeft((int) e));
                if (frac == 1.0) {
                    frac = 0.5;
                    e++;
                }
                return func.applyAsDouble(frac) + func.applyAsDouble(2.0) * e;
            }
            return func.applyAsDouble(x);
        }
        double x = Py.asDouble(arg);
        return math1(x, func, false);
    }

    /** math_pow_impl. */
    static double mathPow(double x, double y) {
        double r;
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            if (Double.isNaN(x)) {
                r = y == 0. ? 1. : x;
            } else if (Double.isNaN(y)) {
                r = x == 1. ? 1. : y;
            } else if (Double.isInfinite(x)) {
                boolean oddY = Double.isFinite(y) && Math.abs(y) % 2.0 == 1.0;
                if (y > 0.) {
                    r = oddY ? x : Math.abs(x);
                } else if (y == 0.) {
                    r = 1.;
                } else {
                    r = oddY ? Math.copySign(0., x) : 0.;
                }
            } else {
                if (Math.abs(x) == 1.0) {
                    r = 1.;
                } else if (y > 0. && Math.abs(x) > 1.0) {
                    r = y;
                } else if (y < 0. && Math.abs(x) < 1.0) {
                    r = -y;
                } else {
                    r = 0.;
                }
            }
            return r;
        }
        r = LibM.pow(x, y);
        if (!Double.isFinite(r)) {
            if (Double.isNaN(r)) {
                throw domain();
            }
            if (x == 0.) {
                throw domain();
            }
            throw range();
        }
        return r;
    }

    /** CPython's vector_norm (math.hypot, math.dist). */
    static double vectorNorm(double[] vec, double max, boolean foundNan) {
        int n = vec.length;
        if (Double.isInfinite(max)) {
            return max;
        }
        if (foundNan) {
            return Double.NaN;
        }
        if (max == 0.0 || n <= 1) {
            return max;
        }
        int maxE = Math.getExponent(max) == Double.MIN_EXPONENT - 1 ? Math.getExponent(max * 0x1p54) + 1 - 54
                : Math.getExponent(max) + 1;
        if (maxE < -1023) {
            double[] v2 = new double[n];
            for (int i = 0; i < n; i++) {
                v2[i] = vec[i] / Double.MIN_NORMAL;
            }
            return Double.MIN_NORMAL * vectorNorm(v2, max / Double.MIN_NORMAL, foundNan);
        }
        double scale = Math.scalb(1.0, -maxE);
        double csum = 1.0;
        double frac1 = 0.0;
        double frac2 = 0.0;
        for (double x0 : vec) {
            double x = x0 * scale;
            double prHi = x * x;
            double prLo = Math.fma(x, x, -prHi);
            double smHi = csum + prHi;
            double smLo = (csum - smHi) + prHi;
            csum = smHi;
            frac1 += prLo;
            frac2 += smLo;
        }
        double h = Math.sqrt(csum - 1.0 + (frac1 + frac2));
        double prHi = -h * h;
        double prLo = Math.fma(-h, h, -prHi);
        double smHi = csum + prHi;
        double smLo = (csum - smHi) + prHi;
        csum = smHi;
        frac1 += prLo;
        frac2 += smLo;
        double x = csum - 1.0 + (frac1 + frac2);
        h += x / (2.0 * h);
        return h / scale;
    }

    /** math.fsum: Shewchuk's exact summation as CPython implements it (math_fsum). */
    static double fsum(List<PyObject> items) {
        List<Double> p = new ArrayList<>();
        double specialSum = 0.0;
        double infSum = 0.0;
        for (PyObject o : items) {
            double x = Py.asDouble(o);
            double xsave = x;
            int i = 0;
            for (int j = 0; j < p.size(); j++) {
                double y = p.get(j);
                if (Math.abs(x) < Math.abs(y)) {
                    double t = x;
                    x = y;
                    y = t;
                }
                double hi = x + y;
                double lo = y - (hi - x);
                if (lo != 0.0) {
                    p.set(i++, lo);
                }
                x = hi;
            }
            while (p.size() > i) {
                p.remove(p.size() - 1);
            }
            if (x != 0.0) {
                if (!Double.isFinite(x)) {
                    if (Double.isFinite(xsave)) {
                        throw new PyException("OverflowError", "intermediate overflow in fsum");
                    }
                    if (Double.isInfinite(xsave)) {
                        infSum += xsave;
                    }
                    specialSum += xsave;
                    p.clear();
                } else {
                    p.add(x);
                }
            }
        }
        if (specialSum != 0.0) {
            if (Double.isNaN(infSum)) {
                throw PyException.valueError("-inf + inf in fsum");
            }
            return specialSum;
        }
        double hi = 0.0;
        int n = p.size();
        if (n > 0) {
            hi = p.get(--n);
            double lo = 0;
            while (n > 0) {
                double x = hi;
                double y = p.get(--n);
                hi = x + y;
                double yr = hi - x;
                lo = y - yr;
                if (lo != 0.0) {
                    break;
                }
            }
            if (n > 0 && ((lo < 0.0 && p.get(n - 1) < 0.0) || (lo > 0.0 && p.get(n - 1) > 0.0))) {
                double y = lo * 2.0;
                double x = hi + y;
                double yr = x - hi;
                if (y == yr) {
                    hi = x;
                }
            }
        }
        return hi;
    }
}
