// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.py;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Python's operators and conversions on {@link PyObject}s: what the compiler calls directly ({@code str(int(x))},
 * {@code -float(x)}, {@code x + y}, {@code x == y}, {@code hash(x)}) and what the evaluator uses for expressions.
 */
public final class Py {
    private Py() {}

    public static final PyNone NONE = PyNone.INSTANCE;
    public static final PyBool TRUE = PyBool.TRUE;
    public static final PyBool FALSE = PyBool.FALSE;

    /** Largest result (in bits, or items/characters for sequences) built before giving up with MemoryError. */
    static final long MAX_SIZE = 1L << 28;

    // ------------------------------------------------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------------------------------------------------

    public static PyInt of(long v) {
        return PyInt.of(v);
    }

    public static PyInt of(BigInteger v) {
        return PyInt.of(v);
    }

    public static PyFloat of(double v) {
        return new PyFloat(v);
    }

    public static PyStr of(String s) {
        return PyStr.of(s);
    }

    public static PyBool of(boolean b) {
        return b ? TRUE : FALSE;
    }

    public static PyList list(PyObject... items) {
        return new PyList(new ArrayList<>(Arrays.asList(items)));
    }

    public static PyList list(List<PyObject> items) {
        return new PyList(new ArrayList<>(items));
    }

    public static PyTuple tuple(PyObject... items) {
        return PyTuple.of(Arrays.asList(items.clone()));
    }

    public static PyTuple tuple(List<PyObject> items) {
        return PyTuple.of(new ArrayList<>(items));
    }

    public static PyDict dict() {
        return new PyDict();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Conversions
    // ------------------------------------------------------------------------------------------------------------

    /** {@code str(x)}. */
    public static String str(PyObject x) {
        return x.str();
    }

    /** {@code repr(x)}. */
    public static String repr(PyObject x) {
        return x.repr();
    }

    /** {@code bool(x)}. */
    public static boolean truthy(PyObject x) {
        return x.truthy();
    }

    /** {@code int(x)}. */
    public static PyObject toInt(PyObject x) {
        if (x instanceof PyBool b) {
            return PyInt.of(b.longValue());
        }
        if (x instanceof PyInt) {
            return x;
        }
        if (x instanceof PyFloat f) {
            return floatToInt(f.value());
        }
        if (x instanceof PyStr s) {
            return NumParse.parseInt(s.value(), 10);
        }
        throw PyException.typeError("int() argument must be a string, a bytes-like object or a real number, not '"
                + x.typeName() + "'");
    }

    /** {@code int(x, base)} for a string x. */
    public static PyObject toInt(PyObject x, int base) {
        if (!(x instanceof PyStr s)) {
            throw PyException.typeError("int() can't convert non-string with explicit base");
        }
        if (base != 0 && (base < 2 || base > 36)) {
            throw PyException.valueError("int() base must be >= 2 and <= 36, or 0");
        }
        return NumParse.parseInt(s.value(), base);
    }

    static PyInt floatToInt(double d) {
        if (Double.isInfinite(d)) {
            throw new PyException("OverflowError", "cannot convert float infinity to integer");
        }
        if (Double.isNaN(d)) {
            throw PyException.valueError("cannot convert float NaN to integer");
        }
        if (Math.abs(d) < 9.0e18) {
            return PyInt.of((long) d);
        }
        return PyInt.of(new BigDecimal(d).toBigInteger());
    }

    /** {@code float(x)}. */
    public static PyObject toFloat(PyObject x) {
        if (x instanceof PyFloat) {
            return x;
        }
        if (x instanceof PyInt i) {
            return of(i.toDouble());
        }
        if (x instanceof PyStr s) {
            return of(NumParse.parseFloat(s.value()));
        }
        throw PyException.typeError("float() argument must be a string or a real number, not '"
                + x.typeName() + "'");
    }

    /** The double value of an int or float operand (TypeError "must be real number, not str" otherwise). */
    static double asDouble(PyObject x) {
        if (x instanceof PyFloat f) {
            return f.value();
        }
        if (x instanceof PyInt i) {
            return i.toDouble();
        }
        throw PyException.typeError("must be real number, not " + x.typeName());
    }

    /** {@code operator.index(x)}: an int, or TypeError "'float' object cannot be interpreted as an integer". */
    static PyInt index(PyObject x) {
        if (x instanceof PyInt i) {
            return i;
        }
        throw PyException.typeError("'" + x.typeName() + "' object cannot be interpreted as an integer");
    }

    /** {@code index(x)} as a Java long (OverflowError if too large). */
    static long indexLong(PyObject x) {
        PyInt i = index(x);
        if (!i.isLong()) {
            throw new PyException("OverflowError", "Python int too large to convert to C ssize_t");
        }
        return i.longValue();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Identity helpers
    // ------------------------------------------------------------------------------------------------------------

    private static final ThreadLocal<IdentityHashMap<Object, Boolean>> REPR_STACK =
            ThreadLocal.withInitial(IdentityHashMap::new);

    /** Py_ReprEnter: false if {@code o} is already being repr'd (self-referential container). */
    static boolean reprEnter(Object o) {
        return REPR_STACK.get().put(o, Boolean.TRUE) == null;
    }

    static void reprLeave(Object o) {
        REPR_STACK.get().remove(o);
    }

    /** An address-like hex string for reprs of objects whose CPython repr shows an address (never compared). */
    static String fakeAddress(Object o) {
        return String.format("0x7f%010x", (System.identityHashCode(o) & 0xffffffffL) << 4);
    }

    static long identityHash(Object o) {
        return (System.identityHashCode(o) & 0xffffffffL) << 4 >>> 4;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Hashing (CPython 3.13, 64-bit, PYTHONHASHSEED=0)
    // ------------------------------------------------------------------------------------------------------------

    private static final long HASH_MODULUS = (1L << 61) - 1;
    private static final int HASH_BITS = 61;
    private static final BigInteger HASH_MODULUS_BIG = BigInteger.valueOf(HASH_MODULUS);

    /** {@code hash(x)}. */
    public static long hash(PyObject x) {
        return x.hash();
    }

    static long hashInt(PyInt i) {
        long h;
        if (i.isLong()) {
            long v = i.longValue();
            if (v == Long.MIN_VALUE) {
                h = -(Long.remainderUnsigned(Long.MIN_VALUE, HASH_MODULUS));
            } else {
                h = Math.abs(v) % HASH_MODULUS;
                if (v < 0) {
                    h = -h;
                }
            }
        } else {
            BigInteger v = i.value();
            h = v.abs().mod(HASH_MODULUS_BIG).longValue();
            if (v.signum() < 0) {
                h = -h;
            }
        }
        return h == -1 ? -2 : h;
    }

    static long hashDouble(double v, Object inst) {
        if (!Double.isFinite(v)) {
            if (Double.isInfinite(v)) {
                return v > 0 ? 314159 : -314159;
            }
            return identityHash(inst);
        }
        if (v == 0) {
            return 0;
        }
        // frexp: v = m * 2**e with 0.5 <= |m| < 1
        int e;
        if (Math.getExponent(v) == Double.MIN_EXPONENT - 1) {
            e = Math.getExponent(v * 0x1p54) + 1 - 54;
        } else {
            e = Math.getExponent(v) + 1;
        }
        double m = Math.scalb(v, -e);
        long sign = 1;
        if (m < 0) {
            sign = -1;
            m = -m;
        }
        long x = 0;
        while (m != 0) {
            x = ((x << 28) & HASH_MODULUS) | x >>> (HASH_BITS - 28);
            m *= 268435456.0;
            e -= 28;
            long y = (long) m;
            m -= y;
            x += y;
            if (Long.compareUnsigned(x, HASH_MODULUS) >= 0) {
                x -= HASH_MODULUS;
            }
        }
        e = e >= 0 ? e % HASH_BITS : HASH_BITS - 1 - ((-1 - e) % HASH_BITS);
        x = ((x << e) & HASH_MODULUS) | x >>> (HASH_BITS - e);
        x = x * sign;
        return x == -1 ? -2 : x;
    }

    /** hash(str): SipHash-1-3 with a zero key over the string's PEP 393 representation (UCS1/UCS2/UCS4). */
    static long hashString(PyStr s) {
        int n = s.len();
        if (n == 0) {
            return 0;
        }
        int max = 0;
        for (int i = 0; i < n; i++) {
            max = Math.max(max, s.codePointAt(i));
        }
        int kind = max < 256 ? 1 : max < 65536 ? 2 : 4;
        byte[] data = new byte[n * kind];
        for (int i = 0; i < n; i++) {
            int c = s.codePointAt(i);
            for (int b = 0; b < kind; b++) {
                data[i * kind + b] = (byte) (c >>> (8 * b));
            }
        }
        long h = siphash13(data);
        return h == -1 ? -2 : h;
    }

    private static long siphash13(byte[] in) {
        int size = in.length;
        long b = (long) size << 56;
        long v0 = 0x736f6d6570736575L;
        long v1 = 0x646f72616e646f6dL;
        long v2 = 0x6c7967656e657261L;
        long v3 = 0x7465646279746573L;
        int off = 0;
        long[] v = {v0, v1, v2, v3};
        while (size - off >= 8) {
            long mi = 0;
            for (int k = 7; k >= 0; k--) {
                mi = (mi << 8) | (in[off + k] & 0xff);
            }
            off += 8;
            v[3] ^= mi;
            sipRound(v);
            v[0] ^= mi;
        }
        long t = 0;
        for (int k = size - off - 1; k >= 0; k--) {
            t = (t << 8) | (in[off + k] & 0xff);
        }
        b |= t;
        v[3] ^= b;
        sipRound(v);
        v[0] ^= b;
        v[2] ^= 0xff;
        sipRound(v);
        sipRound(v);
        sipRound(v);
        return (v[0] ^ v[1]) ^ (v[2] ^ v[3]);
    }

    private static void sipRound(long[] v) {
        // HALF_ROUND(v0,v1,v2,v3,13,16)
        v[0] += v[1];
        v[2] += v[3];
        v[1] = Long.rotateLeft(v[1], 13) ^ v[0];
        v[3] = Long.rotateLeft(v[3], 16) ^ v[2];
        v[0] = Long.rotateLeft(v[0], 32);
        // HALF_ROUND(v2,v1,v0,v3,17,21)
        v[2] += v[1];
        v[0] += v[3];
        v[1] = Long.rotateLeft(v[1], 17) ^ v[2];
        v[3] = Long.rotateLeft(v[3], 21) ^ v[0];
        v[2] = Long.rotateLeft(v[2], 32);
    }

    static long tupleHash(List<PyObject> items) {
        final long prime1 = 0x9E3779B185EBCA87L;
        final long prime2 = 0xC2B2AE3D27D4EB4FL;
        final long prime5 = 0x27D4EB2F165667C5L;
        long acc = prime5;
        for (PyObject o : items) {
            long lane = o.hash();
            acc += lane * prime2;
            acc = Long.rotateLeft(acc, 31);
            acc *= prime1;
        }
        acc += items.size() ^ (prime5 ^ 3527539L);
        if (acc == -1) {
            return 1546275796L;
        }
        return acc;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Comparison
    // ------------------------------------------------------------------------------------------------------------

    /** {@code a == b}. */
    public static boolean eq(PyObject a, PyObject b) {
        if (a instanceof PyInt x && b instanceof PyInt y) {
            if (x.isLong() && y.isLong()) {
                return x.longValue() == y.longValue();
            }
            return x.value().equals(y.value());
        }
        if (isNumber(a) && isNumber(b)) {
            return numCompare(a, b) == 0;
        }
        if (a == b) {
            return true;
        }
        if (a instanceof PyStr x && b instanceof PyStr y) {
            return x.value().equals(y.value());
        }
        if (a instanceof PyList x && b instanceof PyList y) {
            return seqEq(x.items(), y.items());
        }
        if (a instanceof PyTuple x && b instanceof PyTuple y) {
            return seqEq(x.items(), y.items());
        }
        if (a instanceof PyDict x && b instanceof PyDict y) {
            if (x.size() != y.size()) {
                return false;
            }
            for (Map.Entry<PyObject, PyObject> e : x.entries()) {
                PyObject v = y.get(e.getKey());
                if (v == null || !(v == e.getValue() || eq(e.getValue(), v))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof PySet x && b instanceof PySet y) {
            return x.len() == y.len() && x.isSubset(y);
        }
        if (a instanceof PyRange x && b instanceof PyRange y) {
            if (x.length() != y.length()) {
                return false;
            }
            if (x.length() == 0) {
                return true;
            }
            if (x.start != y.start) {
                return false;
            }
            return x.length() == 1 || x.step == y.step;
        }
        if (a instanceof PySlice x && b instanceof PySlice y) {
            return eq(x.start, y.start) && eq(x.stop, y.stop) && eq(x.step, y.step);
        }
        if (a instanceof PyDictView x && b instanceof PyDictView y && x.typeName().equals("dict_keys")
                && y.typeName().equals("dict_keys")) {
            return x.len() == y.len() && PySet.fromIterable(x, false).isSubset(PySet.fromIterable(y, false));
        }
        return false;
    }

    private static boolean seqEq(List<PyObject> a, List<PyObject> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            PyObject x = a.get(i);
            PyObject y = b.get(i);
            if (x != y && !eq(x, y)) {
                return false;
            }
        }
        return true;
    }

    static boolean isNumber(PyObject o) {
        return o instanceof PyInt || o instanceof PyFloat;
    }

    /** Compares two numbers exactly (int vs float without rounding); NaN compares as "unordered" (2). */
    private static int numCompare(PyObject a, PyObject b) {
        if (a instanceof PyInt x && b instanceof PyInt y) {
            if (x.isLong() && y.isLong()) {
                return Long.compare(x.longValue(), y.longValue());
            }
            return x.value().compareTo(y.value());
        }
        if (a instanceof PyFloat x && b instanceof PyFloat y) {
            double p = x.value();
            double q = y.value();
            if (Double.isNaN(p) || Double.isNaN(q)) {
                return 2;
            }
            return p < q ? -1 : p > q ? 1 : 0;
        }
        if (a instanceof PyFloat x) {
            int c = intFloatCompare((PyInt) b, x.value());
            return c == 2 ? 2 : -c;
        }
        return intFloatCompare((PyInt) a, ((PyFloat) b).value());
    }

    /** sign(i - d), or 2 if d is NaN. */
    private static int intFloatCompare(PyInt i, double d) {
        if (Double.isNaN(d)) {
            return 2;
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? -1 : 1;
        }
        if (i.isLong() && Math.abs(i.longValue()) < (1L << 53)) {
            double v = i.longValue();
            return v < d ? -1 : v > d ? 1 : 0;
        }
        return new BigDecimal(i.value()).compareTo(new BigDecimal(d));
    }

    /**
     * Orders two objects with Python's {@code <} (as {@code sorted} does): negative if {@code a < b}, positive if
     * {@code b < a}, else zero (so NaN compares equal to everything). TypeError for types Python cannot order, e.g.
     * "'<' not supported between instances of 'str' and 'int'".
     */
    public static int compare(PyObject a, PyObject b) {
        if (lt(a, b)) {
            return -1;
        }
        if (lt(b, a)) {
            return 1;
        }
        return 0;
    }

    /** Rich comparison {@code a op b} for {@code < <= > >= == !=}, returning a Python object (sets: subset tests). */
    static PyObject richCompare(String op, PyObject a, PyObject b) {
        switch (op) {
            case "==":
                return of(eq(a, b));
            case "!=":
                return of(!eq(a, b));
            default:
                return of(compareOp(op, a, b));
        }
    }

    static boolean lt(PyObject a, PyObject b) {
        return compareOp("<", a, b);
    }

    /** {@code a op b} for one of {@code < <= > >=}. */
    static boolean compareOp(String op, PyObject a, PyObject b) {
        if (isNumber(a) && isNumber(b)) {
            int c = numCompare(a, b);
            if (c == 2) {
                return false;
            }
            return test(op, c);
        }
        if (a instanceof PyStr x && b instanceof PyStr y) {
            return test(op, compareCodePoints(x.value(), y.value()));
        }
        if (a instanceof PyList x && b instanceof PyList y) {
            return seqCompare(op, x.items(), y.items());
        }
        if (a instanceof PyTuple x && b instanceof PyTuple y) {
            return seqCompare(op, x.items(), y.items());
        }
        if (a instanceof PySet x && b instanceof PySet y) {
            return switch (op) {
                case "<" -> x.len() < y.len() && x.isSubset(y);
                case "<=" -> x.isSubset(y);
                case ">" -> x.len() > y.len() && y.isSubset(x);
                default -> y.isSubset(x);
            };
        }
        throw PyException.typeError("'" + op + "' not supported between instances of '" + a.typeName() + "' and '"
                + b.typeName() + "'");
    }

    private static boolean test(String op, int c) {
        return switch (op) {
            case "<" -> c < 0;
            case "<=" -> c <= 0;
            case ">" -> c > 0;
            case ">=" -> c >= 0;
            default -> throw new IllegalArgumentException(op);
        };
    }

    static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            int x = a.codePointAt(i);
            int y = b.codePointAt(j);
            if (x != y) {
                return x < y ? -1 : 1;
            }
            i += Character.charCount(x);
            j += Character.charCount(y);
        }
        return Boolean.compare(i < a.length(), j < b.length());
    }

    private static boolean seqCompare(String op, List<PyObject> a, List<PyObject> b) {
        int n = Math.min(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            PyObject x = a.get(i);
            PyObject y = b.get(i);
            if (x != y && !eq(x, y)) {
                return compareOp(op, x, y);
            }
        }
        return test(op, Integer.compare(a.size(), b.size()));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Arithmetic
    // ------------------------------------------------------------------------------------------------------------

    /** {@code -x}. */
    public static PyObject neg(PyObject x) {
        if (x instanceof PyInt i) {
            if (i.isLong() && i.longValue() != Long.MIN_VALUE) {
                return PyInt.of(-i.longValue());
            }
            return PyInt.of(i.value().negate());
        }
        if (x instanceof PyFloat f) {
            return of(-f.value());
        }
        throw PyException.typeError("bad operand type for unary -: '" + x.typeName() + "'");
    }

    /** {@code +x}. */
    public static PyObject pos(PyObject x) {
        if (x instanceof PyBool b) {
            return PyInt.of(b.longValue());
        }
        if (x instanceof PyInt || x instanceof PyFloat) {
            return x;
        }
        throw PyException.typeError("bad operand type for unary +: '" + x.typeName() + "'");
    }

    /** {@code ~x}. */
    public static PyObject invert(PyObject x) {
        if (x instanceof PyInt i) {
            if (i.isLong()) {
                return PyInt.of(~i.longValue());
            }
            return PyInt.of(i.value().not());
        }
        throw PyException.typeError("bad operand type for unary ~: '" + x.typeName() + "'");
    }

    /** {@code abs(x)}. */
    static PyObject abs(PyObject x) {
        if (x instanceof PyInt i) {
            return i.signum() < 0 ? neg(i) : PyInt.of(i.value());
        }
        if (x instanceof PyFloat f) {
            return of(Math.abs(f.value()));
        }
        throw PyException.typeError("bad operand type for abs(): '" + x.typeName() + "'");
    }

    /**
     * {@code a op b} for {@code + - * / // % ** << >> & | ^ @} and the comparisons {@code == != < <= > >=}
     * (and {@code in}, {@code not in}, {@code is}, {@code is not}).
     */
    public static PyObject binop(String op, PyObject a, PyObject b) {
        switch (op) {
            case "+":
                return add(a, b);
            case "-":
                return sub(a, b);
            case "*":
                return mul(a, b);
            case "/":
                return truediv(a, b);
            case "//":
                return floordiv(a, b);
            case "%":
                return mod(a, b);
            case "**":
                return pow(a, b);
            case "<<":
            case ">>":
                return shift(op, a, b);
            case "&":
            case "|":
            case "^":
                return bitop(op, a, b);
            case "@":
                throw unsupported(op, a, b);
            case "==":
            case "!=":
            case "<":
            case "<=":
            case ">":
            case ">=":
                return richCompare(op, a, b);
            case "in":
                return of(b.contains(a));
            case "not in":
                return of(!b.contains(a));
            case "is":
                return of(a == b);
            case "is not":
                return of(a != b);
            default:
                throw new IllegalArgumentException("unknown operator " + op);
        }
    }

    private static boolean isSetLikeView(PyObject o) {
        return o instanceof PyDictView v && !v.typeName().equals("dict_values");
    }

    static PyException unsupported(String op, PyObject a, PyObject b) {
        return PyException.typeError("unsupported operand type(s) for " + op + ": '" + a.typeName() + "' and '"
                + b.typeName() + "'");
    }

    private static BigInteger big(PyObject o) {
        return ((PyInt) o).value();
    }

    private static boolean bothLong(PyObject a, PyObject b) {
        return a instanceof PyInt x && b instanceof PyInt y && x.isLong() && y.isLong();
    }

    private static long lv(PyObject o) {
        return ((PyInt) o).longValue();
    }

    private static PyObject add(PyObject a, PyObject b) {
        if (a instanceof PyInt && b instanceof PyInt) {
            if (bothLong(a, b)) {
                long x = lv(a);
                long y = lv(b);
                long r = x + y;
                if (((x ^ r) & (y ^ r)) >= 0) {
                    return PyInt.of(r);
                }
            }
            return PyInt.of(big(a).add(big(b)));
        }
        if (isNumber(a) && isNumber(b)) {
            return of(asDouble(a) + asDouble(b));
        }
        if (a instanceof PyStr x) {
            if (b instanceof PyStr y) {
                checkSize((long) x.value().length() + y.value().length());
                return PyStr.of(x.value() + y.value());
            }
            throw PyException.typeError("can only concatenate str (not \"" + b.typeName() + "\") to str");
        }
        if (a instanceof PyList x) {
            if (b instanceof PyList y) {
                List<PyObject> out = new ArrayList<>(x.items());
                out.addAll(y.items());
                return new PyList(out);
            }
            throw PyException.typeError("can only concatenate list (not \"" + b.typeName() + "\") to list");
        }
        if (a instanceof PyTuple x) {
            if (b instanceof PyTuple y) {
                List<PyObject> out = new ArrayList<>(x.items());
                out.addAll(y.items());
                return PyTuple.of(out);
            }
            throw PyException.typeError("can only concatenate tuple (not \"" + b.typeName() + "\") to tuple");
        }
        throw unsupported("+", a, b);
    }

    private static PyObject sub(PyObject a, PyObject b) {
        if (a instanceof PyInt && b instanceof PyInt) {
            if (bothLong(a, b)) {
                long x = lv(a);
                long y = lv(b);
                long r = x - y;
                if (((x ^ y) & (x ^ r)) >= 0) {
                    return PyInt.of(r);
                }
            }
            return PyInt.of(big(a).subtract(big(b)));
        }
        if (isNumber(a) && isNumber(b)) {
            return of(asDouble(a) - asDouble(b));
        }
        if (a instanceof PySet x && b instanceof PySet y) {
            return x.difference(y);
        }
        if (isSetLikeView(a) || (isSetLikeView(b) && a instanceof PySet)) {
            return PySet.fromIterable(a, false).difference(b instanceof PySet ? b : PySet.fromIterable(b, false));
        }
        throw unsupported("-", a, b);
    }

    private static PyObject mul(PyObject a, PyObject b) {
        if (a instanceof PyInt && b instanceof PyInt) {
            if (bothLong(a, b)) {
                long x = lv(a);
                long y = lv(b);
                long hi = Math.multiplyHigh(x, y);
                long lo = x * y;
                if ((hi == 0 && lo >= 0) || (hi == -1 && lo < 0)) {
                    return PyInt.of(lo);
                }
            }
            BigInteger x = big(a);
            BigInteger y = big(b);
            checkSize((long) x.bitLength() + y.bitLength());
            return PyInt.of(x.multiply(y));
        }
        if (isNumber(a) && isNumber(b)) {
            return of(asDouble(a) * asDouble(b));
        }
        if (isSequence(a)) {
            return repeat(a, b);
        }
        if (isSequence(b)) {
            return repeat(b, a);
        }
        throw unsupported("*", a, b);
    }

    private static boolean isSequence(PyObject o) {
        return o instanceof PyStr || o instanceof PyList || o instanceof PyTuple;
    }

    private static PyObject repeat(PyObject seq, PyObject count) {
        if (!(count instanceof PyInt c)) {
            throw PyException.typeError("can't multiply sequence by non-int of type '" + count.typeName() + "'");
        }
        long n = c.isLong() ? Math.max(0, c.longValue()) : (c.signum() < 0 ? 0 : Long.MAX_VALUE);
        if (seq instanceof PyStr s) {
            if (n == 0 || s.value().isEmpty()) {
                return PyStr.of("");
            }
            checkSize(n * s.value().length() / n == s.value().length() ? n * s.value().length() : Long.MAX_VALUE);
            return PyStr.of(s.value().repeat((int) n));
        }
        List<PyObject> items = ((PySequence) seq).items();
        if (n == 0 || items.isEmpty()) {
            return seq instanceof PyList ? new PyList() : PyTuple.EMPTY;
        }
        checkSize(n > MAX_SIZE ? Long.MAX_VALUE : n * items.size());
        List<PyObject> out = new ArrayList<>((int) (n * items.size()));
        for (long i = 0; i < n; i++) {
            out.addAll(items);
        }
        return seq instanceof PyList ? new PyList(out) : PyTuple.of(out);
    }

    static void checkSize(long n) {
        if (n < 0 || n > MAX_SIZE) {
            throw new PyException("MemoryError", "");
        }
    }

    private static PyObject truediv(PyObject a, PyObject b) {
        if (a instanceof PyInt x && b instanceof PyInt y) {
            if (y.signum() == 0) {
                throw new PyException("ZeroDivisionError", "division by zero");
            }
            if (x.isLong() && y.isLong() && Math.abs(x.longValue()) <= (1L << 53)
                    && Math.abs(y.longValue()) <= (1L << 53)) {
                return of((double) x.longValue() / (double) y.longValue());
            }
            return of(intTrueDivide(x.value(), y.value()));
        }
        if (isNumber(a) && isNumber(b)) {
            double y = asDouble(b);
            if (y == 0) {
                throw new PyException("ZeroDivisionError", "float division by zero");
            }
            return of(asDouble(a) / y);
        }
        throw unsupported("/", a, b);
    }

    /** a / b for ints, correctly rounded (CPython's long_true_divide). */
    static double intTrueDivide(BigInteger a, BigInteger b) {
        boolean negative = a.signum() * b.signum() < 0;
        a = a.abs();
        b = b.abs();
        if (a.signum() == 0) {
            return negative ? -0.0 : 0.0;
        }
        int shift = 55 - (a.bitLength() - b.bitLength());
        BigInteger num = shift >= 0 ? a.shiftLeft(shift) : a;
        BigInteger den = shift >= 0 ? b : b.shiftLeft(-shift);
        BigInteger[] qr = num.divideAndRemainder(den);
        BigInteger q = qr[0];
        boolean sticky = qr[1].signum() != 0;
        int qbits = q.bitLength();
        long exponent = (long) qbits - 1 - shift;
        if (exponent > 1023) {
            throw new PyException("OverflowError", "integer division result too large for a float");
        }
        long precision = exponent >= -1022 ? 53 : 53 - (-1022 - exponent);
        int drop = (int) Math.min(qbits + 1L, qbits - precision);
        long mant;
        if (drop > qbits) {
            mant = 0;
        } else {
            BigInteger kept = q.shiftRight(drop);
            BigInteger rest = q.subtract(kept.shiftLeft(drop));
            mant = kept.longValueExact();
            BigInteger half = BigInteger.ONE.shiftLeft(drop - 1);
            int c = rest.compareTo(half);
            if (c > 0 || (c == 0 && (sticky || (mant & 1) == 1))) {
                mant++;
            }
        }
        double r = Math.scalb((double) mant, drop - shift);
        if (Double.isInfinite(r)) {
            throw new PyException("OverflowError", "integer division result too large for a float");
        }
        return negative ? -r : r;
    }

    private static PyObject floordiv(PyObject a, PyObject b) {
        if (a instanceof PyInt && b instanceof PyInt) {
            return intDivmod(a, b, "integer division or modulo by zero")[0];
        }
        if (isNumber(a) && isNumber(b)) {
            double y = asDouble(b);
            if (y == 0) {
                throw new PyException("ZeroDivisionError", "float floor division by zero");
            }
            return of(floatDivmod(asDouble(a), y)[0]);
        }
        throw unsupported("//", a, b);
    }

    private static PyObject mod(PyObject a, PyObject b) {
        if (a instanceof PyStr s) {
            return of(Formatter.percent(s.value(), b));
        }
        if (a instanceof PyInt && b instanceof PyInt) {
            return intDivmod(a, b, "integer modulo by zero")[1];
        }
        if (isNumber(a) && isNumber(b)) {
            double y = asDouble(b);
            if (y == 0) {
                throw new PyException("ZeroDivisionError", "float modulo by zero");
            }
            return of(floatMod(asDouble(a), y));
        }
        throw unsupported("%", a, b);
    }

    /** {@code divmod(a, b)}. */
    static PyObject divmod(PyObject a, PyObject b) {
        if (a instanceof PyInt && b instanceof PyInt) {
            PyObject[] r = intDivmod(a, b, "integer division or modulo by zero");
            return tuple(r[0], r[1]);
        }
        if (isNumber(a) && isNumber(b)) {
            double y = asDouble(b);
            if (y == 0) {
                throw new PyException("ZeroDivisionError", "float divmod()");
            }
            double[] r = floatDivmod(asDouble(a), y);
            return tuple(of(r[0]), of(r[1]));
        }
        throw PyException.typeError("unsupported operand type(s) for divmod(): '" + a.typeName() + "' and '"
                + b.typeName() + "'");
    }

    private static PyObject[] intDivmod(PyObject a, PyObject b, String zeroMessage) {
        PyInt x = (PyInt) a;
        PyInt y = (PyInt) b;
        if (y.signum() == 0) {
            throw new PyException("ZeroDivisionError", zeroMessage);
        }
        if (x.isLong() && y.isLong() && !(x.longValue() == Long.MIN_VALUE && y.longValue() == -1)) {
            return new PyObject[] {PyInt.of(Math.floorDiv(x.longValue(), y.longValue())),
                PyInt.of(Math.floorMod(x.longValue(), y.longValue()))};
        }
        BigInteger[] qr = x.value().divideAndRemainder(y.value());
        if (qr[1].signum() != 0 && qr[1].signum() != y.signum()) {
            qr[0] = qr[0].subtract(BigInteger.ONE);
            qr[1] = qr[1].add(y.value());
        }
        return new PyObject[] {PyInt.of(qr[0]), PyInt.of(qr[1])};
    }

    /** CPython's float_rem. */
    static double floatMod(double vx, double wx) {
        double mod = vx % wx;
        if (mod != 0) {
            if ((wx < 0) != (mod < 0)) {
                mod += wx;
            }
        } else {
            mod = Math.copySign(0.0, wx);
        }
        return mod;
    }

    /** CPython's _float_div_mod: {floordiv, mod}. */
    static double[] floatDivmod(double vx, double wx) {
        double mod = vx % wx;
        double div = (vx - mod) / wx;
        if (mod != 0) {
            if ((wx < 0) != (mod < 0)) {
                mod += wx;
                div -= 1.0;
            }
        } else {
            mod = Math.copySign(0.0, wx);
        }
        double floordiv;
        if (div != 0) {
            floordiv = Math.floor(div);
            if (div - floordiv > 0.5) {
                floordiv += 1.0;
            }
        } else {
            floordiv = Math.copySign(0.0, vx / wx);
        }
        return new double[] {floordiv, mod};
    }

    /** {@code a ** b}. */
    static PyObject pow(PyObject a, PyObject b) {
        if (a instanceof PyInt x && b instanceof PyInt y) {
            if (y.signum() < 0) {
                return of(floatPow(x.toDouble(), y.toDouble()));
            }
            return intPow(x, y);
        }
        if (isNumber(a) && isNumber(b)) {
            return of(floatPow(asDouble(a), asDouble(b)));
        }
        throw unsupported("** or pow()", a, b);
    }

    private static PyObject intPow(PyInt x, PyInt y) {
        BigInteger base = x.value();
        if (base.signum() == 0) {
            return PyInt.of(y.signum() == 0 ? 1 : 0);
        }
        if (base.equals(BigInteger.ONE)) {
            return PyInt.of(1);
        }
        if (base.equals(BigInteger.ONE.negate())) {
            return PyInt.of(y.value().testBit(0) ? -1 : 1);
        }
        if (!y.isLong() || (double) y.longValue() * base.bitLength() > MAX_SIZE) {
            throw new PyException("MemoryError", "");
        }
        return PyInt.of(base.pow((int) y.longValue()));
    }

    /** {@code pow(a, b, m)} for ints. */
    static PyObject modPow(PyObject a, PyObject b, PyObject m) {
        if (!(a instanceof PyInt x && b instanceof PyInt y && m instanceof PyInt z)) {
            if (isNumber(a) && isNumber(b) && isNumber(m)) {
                throw PyException.typeError("pow() 3rd argument not allowed unless all arguments are integers");
            }
            throw PyException.typeError("unsupported operand type(s) for ** or pow(): '" + a.typeName() + "', '"
                    + b.typeName() + "', '" + m.typeName() + "'");
        }
        BigInteger mod = z.value();
        if (mod.signum() == 0) {
            throw PyException.valueError("pow() 3rd argument cannot be 0");
        }
        BigInteger am = mod.abs();
        if (am.equals(BigInteger.ONE)) {
            return PyInt.of(0);
        }
        BigInteger base = x.value().mod(am);
        BigInteger r;
        if (y.signum() < 0) {
            try {
                base = base.modInverse(am);
            } catch (ArithmeticException e) {
                throw PyException.valueError("base is not invertible for the given modulus");
            }
            r = base.modPow(y.value().negate(), am);
        } else {
            r = base.modPow(y.value(), am);
        }
        if (mod.signum() < 0 && r.signum() != 0) {
            r = r.subtract(am);
        }
        return PyInt.of(r);
    }

    /** CPython's float_pow (the {@code **} operator on floats). */
    static double floatPow(double iv, double iw) {
        if (iw == 0) {
            return 1.0;
        }
        if (Double.isNaN(iv)) {
            return iv;
        }
        if (Double.isNaN(iw)) {
            return iv == 1.0 ? 1.0 : iw;
        }
        if (Double.isInfinite(iw)) {
            iv = Math.abs(iv);
            if (iv == 1.0) {
                return 1.0;
            } else if ((iw > 0.0) == (iv > 1.0)) {
                return Math.abs(iw);
            } else {
                return 0.0;
            }
        }
        if (Double.isInfinite(iv)) {
            boolean odd = isOddInteger(iw);
            if (iw > 0.0) {
                return odd ? iv : Math.abs(iv);
            }
            return odd ? Math.copySign(0.0, iv) : 0.0;
        }
        if (iv == 0.0) {
            boolean odd = isOddInteger(iw);
            if (iw < 0.0) {
                throw new PyException("ZeroDivisionError", "0.0 cannot be raised to a negative power");
            }
            return odd ? iv : 0.0;
        }
        boolean negate = false;
        if (iv < 0.0) {
            if (iw != Math.floor(iw)) {
                throw PyException.valueError("complex numbers are not supported");
            }
            iv = -iv;
            negate = isOddInteger(iw);
        }
        if (iv == 1.0) {
            return negate ? -1.0 : 1.0;
        }
        double ix = LibM.pow(iv, iw);
        if (Double.isInfinite(ix)) {
            throw new PyException("OverflowError", "(34, 'Numerical result out of range')");
        }
        return negate ? -ix : ix;
    }

    static boolean isOddInteger(double x) {
        return Math.abs(x) % 2.0 == 1.0;
    }

    private static PyObject shift(String op, PyObject a, PyObject b) {
        if (!(a instanceof PyInt x && b instanceof PyInt y)) {
            throw unsupported(op, a, b);
        }
        if (y.signum() < 0) {
            throw PyException.valueError("negative shift count");
        }
        if (op.equals(">>")) {
            if (!y.isLong() || y.longValue() > Integer.MAX_VALUE) {
                return PyInt.of(x.signum() < 0 ? -1 : 0);
            }
            return PyInt.of(x.value().shiftRight((int) y.longValue()));
        }
        if (x.signum() == 0) {
            return PyInt.of(0);
        }
        if (!y.isLong() || y.longValue() + x.value().bitLength() > MAX_SIZE) {
            throw new PyException("MemoryError", "");
        }
        return PyInt.of(x.value().shiftLeft((int) y.longValue()));
    }

    private static PyObject bitop(String op, PyObject a, PyObject b) {
        if (a instanceof PyBool x && b instanceof PyBool y) {
            boolean p = x.booleanValue();
            boolean q = y.booleanValue();
            return of(switch (op) {
                case "&" -> p & q;
                case "|" -> p | q;
                default -> p ^ q;
            });
        }
        if (a instanceof PyInt x && b instanceof PyInt y) {
            if (x.isLong() && y.isLong()) {
                long p = x.longValue();
                long q = y.longValue();
                return PyInt.of(switch (op) {
                    case "&" -> p & q;
                    case "|" -> p | q;
                    default -> p ^ q;
                });
            }
            BigInteger p = x.value();
            BigInteger q = y.value();
            return PyInt.of(switch (op) {
                case "&" -> p.and(q);
                case "|" -> p.or(q);
                default -> p.xor(q);
            });
        }
        if (a instanceof PySet x && b instanceof PySet y) {
            return switch (op) {
                case "&" -> x.intersection(y);
                case "|" -> x.union(y);
                default -> x.symmetricDifference(y);
            };
        }
        if (op.equals("|") && a instanceof PyDict x && b instanceof PyDict y) {
            PyDict r = x.copy();
            for (Map.Entry<PyObject, PyObject> e : y.entries()) {
                r.put(e.getKey(), e.getValue());
            }
            return r;
        }
        if (isSetLikeView(a) || isSetLikeView(b)) {
            PySet x = PySet.fromIterable(a, false);
            PyObject y = b;
            return switch (op) {
                case "&" -> x.intersection(y);
                case "|" -> x.union(y);
                default -> x.symmetricDifference(y);
            };
        }
        throw unsupported(op, a, b);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Iteration helpers
    // ------------------------------------------------------------------------------------------------------------

    /** All items of an iterable, as a new Java list. */
    static List<PyObject> toList(PyObject iterable) {
        if (iterable instanceof PySequence s) {
            return new ArrayList<>(s.items());
        }
        List<PyObject> out = new ArrayList<>();
        Iterator<PyObject> it = iterable.iter();
        while (it.hasNext()) {
            out.add(it.next());
            if (out.size() > MAX_SIZE) {
                throw new PyException("MemoryError", "");
            }
        }
        return out;
    }
}
