#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ipc.h>
#include <sys/mman.h>
#include <sys/shm.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define DIR_PATH "/tmp/.kovkod-shm"
#define MAX_MAPS 256

struct map { void *addr; size_t size; int id; };
static struct map maps[MAX_MAPS];
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

static void path_for(int id, char *out, size_t n) { snprintf(out, n, DIR_PATH "/%d", id); }

static int id_for_key(key_t key) { return (int)(((unsigned)key & 0x3fffffff) | 0x40000000); }

int shmget(key_t key, size_t size, int flags) {
    char path[64];
    mkdir(DIR_PATH, 01777);
    chmod(DIR_PATH, 01777);
    int create = flags & IPC_CREAT;
    if (key == IPC_PRIVATE) {
        unsigned seed = (unsigned)getpid() ^ (unsigned)time(NULL);
        for (int i = 0; i < 1000; i++) {
            int id = (int)(rand_r(&seed) & 0x3fffffff) + 1;
            path_for(id, path, sizeof path);
            int fd = open(path, O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC, 0666);
            if (fd < 0) { if (errno == EEXIST) continue; return -1; }
            if (ftruncate(fd, (off_t)size) != 0) { close(fd); unlink(path); return -1; }
            close(fd);
            return id;
        }
        errno = ENOSPC;
        return -1;
    }
    int id = id_for_key(key);
    path_for(id, path, sizeof path);
    int oflags = O_RDWR | O_CLOEXEC | (create ? O_CREAT : 0) | ((flags & IPC_EXCL) ? O_EXCL : 0);
    int fd = open(path, oflags, 0666);
    if (fd < 0) return -1;
    struct stat st;
    if (fstat(fd, &st) == 0 && st.st_size == 0 && create && ftruncate(fd, (off_t)size) != 0) {
        close(fd);
        return -1;
    }
    close(fd);
    return id;
}

void *shmat(int id, const void *where, int flags) {
    static void *(*real)(int, const void *, int);
    char path[64];
    path_for(id, path, sizeof path);
    int fd = open(path, ((flags & SHM_RDONLY) ? O_RDONLY : O_RDWR) | O_CLOEXEC);
    if (fd < 0) {
        if (!real) real = dlsym(RTLD_NEXT, "shmat");
        return real(id, where, flags);
    }
    struct stat st;
    fstat(fd, &st);
    int prot = PROT_READ | ((flags & SHM_RDONLY) ? 0 : PROT_WRITE);
    void *addr = mmap((void *)where, (size_t)st.st_size, prot, MAP_SHARED, fd, 0);
    close(fd);
    if (addr == MAP_FAILED) return (void *)-1;
    pthread_mutex_lock(&lock);
    for (int i = 0; i < MAX_MAPS; i++) {
        if (!maps[i].addr) {
            maps[i].addr = addr;
            maps[i].size = (size_t)st.st_size;
            maps[i].id = id;
            break;
        }
    }
    pthread_mutex_unlock(&lock);
    return addr;
}

int shmdt(const void *addr) {
    static int (*real)(const void *);
    pthread_mutex_lock(&lock);
    for (int i = 0; i < MAX_MAPS; i++) {
        if (maps[i].addr == addr) {
            char path[64], mark[72];
            int id = maps[i].id;
            size_t size = maps[i].size;
            maps[i].addr = NULL;
            pthread_mutex_unlock(&lock);
            munmap((void *)addr, size);
            path_for(id, path, sizeof path);
            snprintf(mark, sizeof mark, "%s.rm", path);
            if (access(mark, F_OK) == 0) { unlink(path); unlink(mark); }
            return 0;
        }
    }
    pthread_mutex_unlock(&lock);
    if (!real) real = dlsym(RTLD_NEXT, "shmdt");
    return real(addr);
}

int shmctl(int id, int cmd, struct shmid_ds *buf) {
    static int (*real)(int, int, struct shmid_ds *);
    char path[64], mark[72];
    struct stat st;
    path_for(id, path, sizeof path);
    if (stat(path, &st) != 0) {
        if (!real) real = dlsym(RTLD_NEXT, "shmctl");
        return real(id, cmd, buf);
    }
    if (cmd == IPC_RMID) {
        snprintf(mark, sizeof mark, "%s.rm", path);
        close(open(mark, O_WRONLY | O_CREAT | O_CLOEXEC, 0666));
        return 0;
    }
    if (cmd == IPC_STAT) {
        memset(buf, 0, sizeof *buf);
        buf->shm_perm.uid = buf->shm_perm.cuid = geteuid();
        buf->shm_perm.gid = buf->shm_perm.cgid = getegid();
        buf->shm_perm.mode = 0666;
        buf->shm_segsz = (size_t)st.st_size;
        buf->shm_nattch = 1;
        return 0;
    }
    if (cmd == IPC_SET) return 0;
    errno = EINVAL;
    return -1;
}
