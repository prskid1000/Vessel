/* Written by build/x11-sysroot.sh. bionic has no <values.h>; this is the
 * legacy BSD spelling of <limits.h>, which libxshmfence's futex backend
 * includes for MAXINT. Not installed on the device — build-time only. */
#ifndef _VESSEL_VALUES_H
#define _VESSEL_VALUES_H
#include <limits.h>
#include <float.h>
#define BITSPERBYTE CHAR_BIT
#define CHARBITS    CHAR_BIT
#define SHORTBITS   (sizeof(short) * CHAR_BIT)
#define INTBITS     (sizeof(int) * CHAR_BIT)
#define LONGBITS    (sizeof(long) * CHAR_BIT)
#define PTRBITS     (sizeof(void *) * CHAR_BIT)
#define MAXSHORT    SHRT_MAX
#define MAXINT      INT_MAX
#define MAXLONG     LONG_MAX
#define MINSHORT    SHRT_MIN
#define MININT      INT_MIN
#define MINLONG     LONG_MIN
#define MAXDOUBLE   DBL_MAX
#define MAXFLOAT    FLT_MAX
#define MINDOUBLE   DBL_MIN
#define MINFLOAT    FLT_MIN
#define DMINEXP     DBL_MIN_EXP
#define FMINEXP     FLT_MIN_EXP
#define DMAXEXP     DBL_MAX_EXP
#define FMAXEXP     FLT_MAX_EXP
#endif
