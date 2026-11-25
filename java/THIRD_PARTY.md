# Third-party code in the Java compiler

`src/main/java/com/moddingfromamod/cbscript/py/LibM.java` and `LibMTables.java` translate the GNU C Library's
double-precision math functions (glibc 2.39, `sysdeps/ieee754/dbl-64`) so that compile-time `$math.$sin(...)` and
friends give CPython-on-Linux's exact last bit (glibc is not correctly rounded, and Java's `Math`/`StrictMath` round
differently). They are **not** covered by this repository's `LICENSE`:

| Functions | Origin | Licence |
|---|---|---|
| sin, cos, tan, atan, atan2, asin, acos, `__branred` (and tables) | IBM Accurate Mathematical Library, as distributed in glibc | LGPL-2.1-or-later |
| exp, log, log2, pow (and tables) | Arm optimized-routines, as distributed in glibc | MIT / Apache-2.0 (Arm's own release); LGPL-2.1-or-later in glibc |
| log10, expm1, sinh, cosh, tanh | Sun fdlibm | Sun's permissive notice |

**Open question (not yet decided):** whether shipping the LGPL-derived part inside the mod is acceptable. If it is
not, those seven functions can be replaced by a permissive implementation; the cost is that their results may differ
from Python's in the last digit for roughly 0.1–0.4% of arguments, which the corpus would then show as differences.
