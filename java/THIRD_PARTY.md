# Licences of the Java compiler's code

All of `java/` is this repository's code (a translation of CBScript, under the permission and `LICENSE` described in
`NOTICE.md`), with these specifics in the compile-time math (`src/main/java/com/moddingfromamod/cbscript/py/`):

| File | What | Licence |
|---|---|---|
| `CRMath.java`, `LibM.java` | Correctly rounded sin, cos, tan, asin, acos, atan, atan2, exp, log, log2, pow; dispatch | Written for this project: `LICENSE` (MPL-2.0) |
| `FdHyp.java` | sinh, cosh, tanh by fdlibm's formulas (re-written here, no code copied) | fdlibm's notice, which the file reproduces: "Copyright (C) 1993 by Sun Microsystems, Inc. ... Permission to use, copy, modify, and distribute this software is freely granted, provided that this notice is preserved." |
| (calls only) | `java.lang.StrictMath.expm1`, `log10`, and for infinities/NaN | The JDK's own API; nothing copied |

No code from any C library is included. Why results can still differ from Python in a last bit: `JAVA_PORT.md`,
"Compile-time math".
