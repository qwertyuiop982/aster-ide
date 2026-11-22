#define _GNU_SOURCE
#undef _FORTIFY_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/time.h>
#include <sys/vfs.h>
#include <unistd.h>
#include <utime.h>

extern char **environ;

static int path_under(const char *path, const char *base) {
    if (!base || base[0] != '/') return 0;
    size_t length = strlen(base);
    return strncmp(path, base, length) == 0 && (!path[length] || path[length] == '/');
}

static int termux_user_path(const char *path, const char **suffix) {
    static const char prefix[] = "/data/user/";
    static const char package[] = "com.termux";
    if (strncmp(path, prefix, sizeof(prefix) - 1) != 0) return 0;
    const char *user = path + sizeof(prefix) - 1;
    const char *separator = strchr(user, '/');
    if (!separator || separator == user) return 0;
    for (const char *digit = user; digit < separator; ++digit) {
        if (*digit < '0' || *digit > '9') return 0;
    }
    const char *application = separator + 1;
    size_t package_length = sizeof(package) - 1;
    if (strncmp(application, package, package_length) != 0 ||
        (application[package_length] && application[package_length] != '/')) return 0;
    *suffix = application + package_length;
    return 1;
}

// Compatibility for dynamically linked Termux tools only. Paths outside these aliases
// (including cwd and other apps' private paths) are resolved by Android as usual.
static const char *translate(const char *path, char buffer[PATH_MAX]) {
    if (!path || path[0] != '/' || !getenv("ASTER_PRIVATE_FILES")) return path;
    const char *original = path;
    // dpkg unpacks Termux archives under an app-private installation root. Only the
    // package payload is redirected; archive entries for /data etc. stay there.
    const char *package_root = getenv("ASTER_APT_ROOT");
    if (path_under(path, package_root)) path += strlen(package_root);
    else if (path_under(path, getenv("ASTER_RUNTIME_ROOT"))) return path;
    static const struct { const char *alias; const char *variable; const char *subdir; } mappings[] = {
        {"/data/data/com.termux", "ASTER_RUNTIME_ROOT", ""},
        {"/usr/bin", "PREFIX", "/bin"},
        {"/bin", "PREFIX", "/bin"},
    };
    const char *suffix = NULL;
    const char *variable = NULL;
    const char *subdir = "";
    for (size_t i = 0; i < sizeof(mappings) / sizeof(*mappings); ++i) {
        size_t length = strlen(mappings[i].alias);
        if (strncmp(path, mappings[i].alias, length) != 0 || (path[length] && path[length] != '/')) continue;
        suffix = path + length;
        variable = mappings[i].variable;
        subdir = mappings[i].subdir;
        break;
    }
    if (!variable && termux_user_path(path, &suffix)) variable = "ASTER_RUNTIME_ROOT";
    if (!variable) return original;

    const char *base = getenv(variable);
    if (!base || base[0] != '/') return original;
    size_t base_len = strlen(base), subdir_len = strlen(subdir), suffix_len = strlen(suffix);
    if (base_len + subdir_len + suffix_len >= PATH_MAX) return "";
    memcpy(buffer, base, base_len);
    memcpy(buffer + base_len, subdir, subdir_len);
    memcpy(buffer + base_len + subdir_len, suffix, suffix_len + 1);
    return buffer;
}

#define NEXT(name, result, signature) \
    result (*next) signature = (result (*) signature)dlsym(RTLD_NEXT, #name)
#define PATH_FUNCTION(result, name, signature, arguments) \
    result name signature { \
        NEXT(name, result, signature); \
        if (!next) { errno = ENOSYS; return (result)(intptr_t)-1; } \
        char p[PATH_MAX]; \
        return next arguments; \
    }
#define TWO_PATHS(name, signature, arguments) \
    int name signature { \
        NEXT(name, int, signature); \
        if (!next) { errno = ENOSYS; return -1; } \
        char p[PATH_MAX], q[PATH_MAX]; \
        return next arguments; \
    }
#define MAP(path) translate(path, p)
#define MAP2(path) translate(path, q)

static int mode_argument(int flags) {
    return (flags & O_CREAT) || ((flags & O_TMPFILE) == O_TMPFILE);
}

#define OPEN(name) \
    int name(const char *path, int flags, ...) { \
        NEXT(name, int, (const char *, int, ...)); \
        if (!next) { errno = ENOSYS; return -1; } \
        mode_t mode = 0; \
        if (mode_argument(flags)) { va_list args; va_start(args, flags); mode = va_arg(args, int); va_end(args); } \
        char p[PATH_MAX]; \
        return next(MAP(path), flags, mode); \
    }
#define OPENAT(name) \
    int name(int fd, const char *path, int flags, ...) { \
        NEXT(name, int, (int, const char *, int, ...)); \
        if (!next) { errno = ENOSYS; return -1; } \
        mode_t mode = 0; \
        if (mode_argument(flags)) { va_list args; va_start(args, flags); mode = va_arg(args, int); va_end(args); } \
        char p[PATH_MAX]; \
        return next(fd, MAP(path), flags, mode); \
    }

OPEN(open)
OPEN(open64)
OPENAT(openat)
OPENAT(openat64)
PATH_FUNCTION(int, __open_2, (const char *path, int flags), (MAP(path), flags))
PATH_FUNCTION(int, __open64_2, (const char *path, int flags), (MAP(path), flags))
PATH_FUNCTION(int, __openat_2, (int fd, const char *path, int flags), (fd, MAP(path), flags))
PATH_FUNCTION(int, __openat64_2, (int fd, const char *path, int flags), (fd, MAP(path), flags))
PATH_FUNCTION(int, creat, (const char *path, mode_t mode), (MAP(path), mode))
PATH_FUNCTION(int, access, (const char *path, int mode), (MAP(path), mode))
PATH_FUNCTION(int, faccessat, (int fd, const char *path, int mode, int flags), (fd, MAP(path), mode, flags))
PATH_FUNCTION(int, stat, (const char *path, struct stat *out), (MAP(path), out))
PATH_FUNCTION(int, lstat, (const char *path, struct stat *out), (MAP(path), out))
PATH_FUNCTION(int, fstatat, (int fd, const char *path, struct stat *out, int flags), (fd, MAP(path), out, flags))
PATH_FUNCTION(int, stat64, (const char *path, struct stat64 *out), (MAP(path), out))
PATH_FUNCTION(int, lstat64, (const char *path, struct stat64 *out), (MAP(path), out))
PATH_FUNCTION(int, fstatat64, (int fd, const char *path, struct stat64 *out, int flags), (fd, MAP(path), out, flags))
PATH_FUNCTION(int, statfs, (const char *path, struct statfs *out), (MAP(path), out))
PATH_FUNCTION(int, statvfs, (const char *path, struct statvfs *out), (MAP(path), out))
PATH_FUNCTION(int, chdir, (const char *path), (MAP(path)))
PATH_FUNCTION(int, chmod, (const char *path, mode_t mode), (MAP(path), mode))
PATH_FUNCTION(int, fchmodat, (int fd, const char *path, mode_t mode, int flags), (fd, MAP(path), mode, flags))
PATH_FUNCTION(int, chown, (const char *path, uid_t uid, gid_t gid), (MAP(path), uid, gid))
PATH_FUNCTION(int, lchown, (const char *path, uid_t uid, gid_t gid), (MAP(path), uid, gid))
PATH_FUNCTION(int, fchownat, (int fd, const char *path, uid_t uid, gid_t gid, int flags), (fd, MAP(path), uid, gid, flags))
PATH_FUNCTION(int, mkdir, (const char *path, mode_t mode), (MAP(path), mode))
PATH_FUNCTION(int, mkdirat, (int fd, const char *path, mode_t mode), (fd, MAP(path), mode))
PATH_FUNCTION(int, mkfifo, (const char *path, mode_t mode), (MAP(path), mode))
PATH_FUNCTION(int, mknod, (const char *path, mode_t mode, dev_t device), (MAP(path), mode, device))
PATH_FUNCTION(int, remove, (const char *path), (MAP(path)))
PATH_FUNCTION(int, rmdir, (const char *path), (MAP(path)))
PATH_FUNCTION(int, unlink, (const char *path), (MAP(path)))
PATH_FUNCTION(int, unlinkat, (int fd, const char *path, int flags), (fd, MAP(path), flags))
PATH_FUNCTION(ssize_t, readlink, (const char *path, char *out, size_t size), (MAP(path), out, size))
PATH_FUNCTION(ssize_t, readlinkat, (int fd, const char *path, char *out, size_t size), (fd, MAP(path), out, size))
PATH_FUNCTION(int, utime, (const char *path, const struct utimbuf *times), (MAP(path), times))
PATH_FUNCTION(int, utimes, (const char *path, const struct timeval times[2]), (MAP(path), times))
PATH_FUNCTION(int, utimensat, (int fd, const char *path, const struct timespec times[2], int flags), (fd, MAP(path), times, flags))
PATH_FUNCTION(int, truncate, (const char *path, off_t size), (MAP(path), size))
TWO_PATHS(rename, (const char *src, const char *dst), (MAP(src), MAP2(dst)))
TWO_PATHS(renameat, (int a, const char *src, int b, const char *dst), (a, MAP(src), b, MAP2(dst)))
TWO_PATHS(link, (const char *src, const char *dst), (MAP(src), MAP2(dst)))
TWO_PATHS(linkat, (int a, const char *src, int b, const char *dst, int flags), (a, MAP(src), b, MAP2(dst), flags))
TWO_PATHS(symlink, (const char *src, const char *dst), (MAP(src), MAP2(dst)))
TWO_PATHS(symlinkat, (const char *src, int fd, const char *dst), (MAP(src), fd, MAP2(dst)))

#define POINTER_FUNCTION(type, name, signature, arguments) \
    type *name signature { \
        NEXT(name, type *, signature); \
        char p[PATH_MAX]; \
        return next ? next arguments : NULL; \
    }
POINTER_FUNCTION(DIR, opendir, (const char *path), (MAP(path)))
POINTER_FUNCTION(FILE, fopen, (const char *path, const char *mode), (MAP(path), mode))
POINTER_FUNCTION(FILE, fopen64, (const char *path, const char *mode), (MAP(path), mode))
POINTER_FUNCTION(FILE, freopen, (const char *path, const char *mode, FILE *stream), (MAP(path), mode, stream))
POINTER_FUNCTION(char, realpath, (const char *path, char *out), (MAP(path), out))
POINTER_FUNCTION(void, dlopen, (const char *path, int flags), (MAP(path), flags))

// The kernel does not see preload mappings when resolving a script's interpreter.
// Honor execute permission on the script before forwarding a legacy interpreter.
static int shebang(const char *path, char data[256], char **interpreter, char **option) {
    if (!getenv("ASTER_PRIVATE_FILES") || access(path, X_OK) != 0) return 0;
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return 0;
    ssize_t length = read(fd, data, 255);
    close(fd);
    if (length < 3 || data[0] != '#' || data[1] != '!') return 0;
    data[length] = 0;
    char *end = strpbrk(data + 2, "\r\n");
    if (!end && length == 255) return 0;
    if (!end) end = data + length;
    while (end > data + 2 && (end[-1] == ' ' || end[-1] == '\t')) --end;
    *end = 0;
    *interpreter = data + 2 + strspn(data + 2, " \t");
    if (**interpreter != '/') return 0;
    char *space = strpbrk(*interpreter, " \t");
    *option = NULL;
    if (space) {
        *space++ = 0;
        *option = space + strspn(space, " \t");
    }
    return 1;
}

static size_t arg_count(char *const argv[]) {
    size_t i = 0;
    if (argv) while (argv[i]) ++i;
    return i;
}

static void script_args(char *out[], const char *interpreter, const char *option,
                        const char *path, char *const argv[], size_t count) {
    size_t j = 0;
    out[j++] = (char *)interpreter;
    if (option) out[j++] = (char *)option;
    out[j++] = (char *)path;
    for (size_t i = 1; i < count; ++i) out[j++] = argv[i];
    out[j] = NULL;
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    NEXT(execve, int, (const char *, char *const [], char *const []));
    if (!next) { errno = ENOSYS; return -1; }
    char p[PATH_MAX];
    const char *file = MAP(path);
    char line[256], *interpreter, *option, target[PATH_MAX];
    if (shebang(file, line, &interpreter, &option)) {
        const char *mapped = translate(interpreter, target);
        if (mapped != interpreter) {
            size_t count = arg_count(argv);
            char *arguments[count + 4];
            script_args(arguments, mapped, option, file, argv, count);
            return next(mapped, arguments, envp);
        }
    }
    return next(file, argv, envp);
}

int execv(const char *path, char *const argv[]) { return execve(path, argv, environ); }

static int for_path(const char *file, char candidate[PATH_MAX], const char **remaining) {
    const char *part = *remaining;
    if (!part) return 0;
    const char *end = strchr(part, ':');
    size_t length = end ? (size_t)(end - part) : strlen(part);
    *remaining = end ? end + 1 : NULL;
    size_t file_len = strlen(file);
    if (length + file_len + 2 >= PATH_MAX) { candidate[0] = 0; return 1; }
    if (length) {
        memcpy(candidate, part, length);
        candidate[length++] = '/';
    }
    memcpy(candidate + length, file, file_len + 1);
    return 1;
}

static int exec_with_shell(const char *file, char *const argv[], char *const envp[]) {
    execve(file, argv, envp);
    if (errno != ENOEXEC) return -1;
    size_t count = arg_count(argv);
    char *arguments[count + 4];
    script_args(arguments, "/bin/sh", NULL, file, argv, count);
    return execve("/bin/sh", arguments, envp);
}

int execvpe(const char *file, char *const argv[], char *const envp[]) {
    if (!*file) { errno = ENOENT; return -1; }
    if (strchr(file, '/')) return exec_with_shell(file, argv, envp);
    const char *search = getenv("PATH");
    if (!search) search = "/system/bin";
    int denied = 0;
    char candidate[PATH_MAX];
    while (for_path(file, candidate, &search)) {
        if (!*candidate) continue;
        exec_with_shell(candidate, argv, envp);
        if (errno == EACCES) denied = 1;
        else if (errno != ENOENT && errno != ENOTDIR) return -1;
    }
    errno = denied ? EACCES : ENOENT;
    return -1;
}

int execvp(const char *file, char *const argv[]) { return execvpe(file, argv, environ); }

static int exec_variadic(const char *file, const char *arg, va_list source, int search, int explicit_env) {
    va_list copy;
    va_copy(copy, source);
    size_t count = 0;
    for (const char *item = arg; item; item = va_arg(copy, const char *)) ++count;
    va_end(copy);
    char *argv[count + 1];
    for (size_t i = 0; i < count; ++i) {
        argv[i] = (char *)arg;
        arg = va_arg(source, const char *);
    }
    argv[count] = NULL;
    char *const *envp = explicit_env ? va_arg(source, char *const *) : environ;
    return search ? execvpe(file, argv, envp) : execve(file, argv, envp);
}

#define EXEC_VARIADIC(name, search, explicit_env) \
    int name(const char *file, const char *arg, ...) { \
        va_list args; va_start(args, arg); \
        int result = exec_variadic(file, arg, args, search, explicit_env); \
        va_end(args); return result; \
    }
EXEC_VARIADIC(execl, 0, 0)
EXEC_VARIADIC(execlp, 1, 0)
EXEC_VARIADIC(execle, 0, 1)

static int spawn_compat(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                        const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    NEXT(posix_spawn, int, (pid_t *, const char *, const posix_spawn_file_actions_t *,
                           const posix_spawnattr_t *, char *const [], char *const []));
    if (!next) return ENOSYS;
    char p[PATH_MAX];
    const char *file = MAP(path);
    char line[256], *interpreter, *option, target[PATH_MAX];
    if (shebang(file, line, &interpreter, &option)) {
        const char *mapped = translate(interpreter, target);
        if (mapped != interpreter) {
            size_t count = arg_count(argv);
            char *arguments[count + 4];
            script_args(arguments, mapped, option, file, argv, count);
            return next(pid, mapped, actions, attr, arguments, envp);
        }
    }
    return next(pid, file, actions, attr, argv, envp);
}

int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    return spawn_compat(pid, path, actions, attr, argv, envp);
}

int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *actions,
                 const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    if (!*file) return ENOENT;
    if (strchr(file, '/')) return spawn_compat(pid, file, actions, attr, argv, envp);
    const char *search = getenv("PATH");
    if (!search) search = "/system/bin";
    int denied = 0;
    char candidate[PATH_MAX];
    while (for_path(file, candidate, &search)) {
        if (!*candidate) continue;
        int status = spawn_compat(pid, candidate, actions, attr, argv, envp);
        if (status == EACCES) denied = 1;
        else if (status != ENOENT && status != ENOTDIR) return status;
    }
    return denied ? EACCES : ENOENT;
}
