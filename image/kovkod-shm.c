#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdarg.h>
#include <sys/syscall.h>
long syscall(long n, ...) {
    static long (*real)(long, ...);
    va_list v;
    va_start(v, n);
    long a = va_arg(v, long), b = va_arg(v, long), c = va_arg(v, long);
    long d = va_arg(v, long), e = va_arg(v, long), f = va_arg(v, long);
    va_end(v);
    if (n == SYS_memfd_create) {
        errno = ENOSYS;
        return -1;
    }
    if (!real) real = dlsym(RTLD_NEXT, "syscall");
    return real(n, a, b, c, d, e, f);
}
int memfd_create(const char *name, unsigned flags) {
    (void)name;
    (void)flags;
    errno = ENOSYS;
    return -1;
}
