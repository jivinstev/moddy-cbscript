// Java port of CBScript (c) SethBling, used with permission; see NOTICE.md. Moddy's modifications: MPL-2.0 (LICENSE).
package com.moddingfromamod.cbscript.compiler;

/**
 * CPython's {@code hash(str)} with {@code PYTHONHASHSEED=0} (SipHash-1-3, zero key, over the string's internal
 * 1/2/4-byte representation).
 *
 * <p>The compiler uses {@code hash()} of a selector name for indexed entities. In a normal Python run that is
 * randomised per process, so no output is "the" right one; the corpus goldens are made with PYTHONHASHSEED=0 and
 * this reproduces them.
 */
final class PyHash {
    private PyHash() {
    }

    static long hash(String s) {
        int n = s.codePointCount(0, s.length());
        if (n == 0) {
            return 0;
        }
        int max = 0;
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            max = Math.max(max, c);
            i += Character.charCount(c);
        }
        int kind = max < 256 ? 1 : max < 65536 ? 2 : 4;
        byte[] data = new byte[n * kind];
        int k = 0;
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            i += Character.charCount(c);
            for (int b = 0; b < kind; b++) {
                data[k++] = (byte) (c >>> (8 * b));
            }
        }
        long x = siphash13(0, 0, data);
        return x == -1 ? -2 : x;
    }

    private static long siphash13(long k0, long k1, byte[] in) {
        long b = ((long) in.length) << 56;
        long v0 = k0 ^ 0x736f6d6570736575L;
        long v1 = k1 ^ 0x646f72616e646f6dL;
        long v2 = k0 ^ 0x6c7967656e657261L;
        long v3 = k1 ^ 0x7465646279746573L;
        int off = 0;
        int left = in.length;
        long[] v = {v0, v1, v2, v3};
        while (left >= 8) {
            long mi = 0;
            for (int i = 7; i >= 0; i--) {
                mi = (mi << 8) | (in[off + i] & 0xffL);
            }
            off += 8;
            left -= 8;
            v[3] ^= mi;
            round(v);
            v[0] ^= mi;
        }
        long t = 0;
        for (int i = left - 1; i >= 0; i--) {
            t = (t << 8) | (in[off + i] & 0xffL);
        }
        b |= t;
        v[3] ^= b;
        round(v);
        v[0] ^= b;
        v[2] ^= 0xff;
        round(v);
        round(v);
        round(v);
        return (v[0] ^ v[1]) ^ (v[2] ^ v[3]);
    }

    private static void round(long[] v) {
        // HALF_ROUND(v0, v1, v2, v3, 13, 16)
        v[0] += v[1];
        v[2] += v[3];
        v[1] = Long.rotateLeft(v[1], 13) ^ v[0];
        v[3] = Long.rotateLeft(v[3], 16) ^ v[2];
        v[0] = Long.rotateLeft(v[0], 32);
        // HALF_ROUND(v2, v1, v0, v3, 17, 21)
        v[2] += v[1];
        v[0] += v[3];
        v[1] = Long.rotateLeft(v[1], 17) ^ v[2];
        v[3] = Long.rotateLeft(v[3], 21) ^ v[0];
        v[2] = Long.rotateLeft(v[2], 32);
    }

    public static void main(String[] args) {
        for (String a : args) {
            System.out.println(hash(a));
        }
    }
}
