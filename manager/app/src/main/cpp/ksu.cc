//
// Created by weishu on 2022/12/9.
//

#include <sys/prctl.h>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <unistd.h>
#include <utility>
#include <android/log.h>
#include <dirent.h>
#include <cstdlib>

#include <unistd.h>
#include <climits>
#include <sys/syscall.h>
#include <cerrno>
#include <vector>
#include <algorithm>
#include <mutex>
#include <fcntl.h>
#include "ksu.h"

static int fd = -1;
static std::mutex driver_mutex;

static bool is_own_driver(int candidate) {
    char path[64];
    char target[PATH_MAX];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", candidate);
    ssize_t n = readlink(path, target, sizeof(target) - 1);
    if (n < 0) return false;
    target[n] = '\0';
    return strcmp(target, "anon_inode:[xecpro_driver]") == 0;
}

static inline int scan_driver_fd() {
    DIR *dir = opendir("/proc/self/fd");
    if (!dir) {
        return -1;
    }

    int found = -1;
    struct dirent *de;
    char path[64];
    char target[PATH_MAX];

    while ((de = readdir(dir)) != NULL) {
        if (de->d_name[0] == '.') {
            continue;
        }

        char *endptr = NULL;
        long fd_long = strtol(de->d_name, &endptr, 10);
        if (!de->d_name[0] || *endptr != '\0' || fd_long < 0 || fd_long > INT_MAX) {
            continue;
        }

        snprintf(path, sizeof(path), "/proc/self/fd/%s", de->d_name);
        ssize_t n = readlink(path, target, sizeof(target) - 1);
        if (n < 0) {
            continue;
        }
        target[n] = '\0';

        if (strcmp(target, "anon_inode:[xecpro_driver]") == 0 &&
            (found < 0 || fd_long < found)) {
            found = (int)fd_long;
        }
    }

    closedir(dir);
    if (found < 0) return -1;
    const int owned = fcntl(found, F_DUPFD_CLOEXEC, 0);
    if (owned >= 0 && !is_own_driver(owned)) {
        close(owned);
        return -1;
    }
    return owned;
}

template<typename... Args>
static int ksuctl(unsigned long op, Args &&... args) {
    static_assert(sizeof...(Args) <= 1, "ioctl expects at most one extra argument");
    // All JNI commands share the descriptor; serialize invalidation/recovery.
    std::lock_guard<std::mutex> lock(driver_mutex);
    if (fd >= 0 && !is_own_driver(fd)) {
        // A reused numeric descriptor is no longer ours to close.
        fd = -1;
    }
    if (fd < 0) {
        fd = scan_driver_fd();
    }
    if (fd < 0) {
        errno = ENODEV;
        return -1;
    }
    const int result = ioctl(fd, op, std::forward<Args>(args)...);
    if (result < 0 && (errno == EBADF || errno == ENOTTY)) {
        const int saved_errno = errno;
        if (is_own_driver(fd)) close(fd);
        fd = -1;
        errno = saved_errno;
    }
    return result;
}

struct ksu_get_info_cmd get_info() {
    // Both version and flags depend on current manager identity. A cached
    // compatibility version must not survive crowning or a lost connection.
    struct ksu_get_info_cmd info {};
    if (ksuctl(KSU_IOCTL_GET_INFO, &info) == 0) {
        return info;
    }
    info = {};
    if (ksuctl(KSU_IOCTL_GET_INFO_LEGACY, &info) < 0) {
        return {};
    }
    info.uapi_version = 0;
    return info;
}

uint32_t get_kernel_uapi_version() {
    auto info = get_info();
    return info.uapi_version;
}

uint32_t get_manager_uapi_version() {
    return KERNEL_SU_UAPI_VERSION;
}

uint32_t get_version() {
    auto info = get_info();
    return info.version;
}

bool get_allow_list(struct ksu_new_get_allow_list_cmd *cmd) {
    return ksuctl(KSU_IOCTL_NEW_GET_ALLOW_LIST, cmd) == 0;
}

bool is_safe_mode() {
    struct ksu_check_safemode_cmd cmd = {};
    ksuctl(KSU_IOCTL_CHECK_SAFEMODE, &cmd);
    return cmd.in_safe_mode;
}

bool is_lkm_mode() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_LKM) != 0;
    }
    return false;
}

bool is_lkm_bundled() {
    auto info = get_info();
    return (info.flags & KSU_GET_INFO_FLAG_LKM) != 0 &&
           (info.flags & KSU_GET_INFO_FLAG_BUNDLED) != 0;
}

bool is_late_load_mode() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_LATE_LOAD) != 0;
    }
    return false;
}

bool is_manager() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_MANAGER) != 0;
    }
    return false;
}

bool is_pr_build() {
    auto info = get_info();
    if (info.version > 0) {
        return (info.flags & KSU_GET_INFO_FLAG_PR_BUILD) != 0;
    }
    return false;
}

bool uid_should_umount(int uid) {
    struct ksu_uid_should_umount_cmd cmd = {};
    cmd.uid = uid;
    ksuctl(KSU_IOCTL_UID_SHOULD_UMOUNT, &cmd);
    return cmd.should_umount;
}

bool set_app_profile(const app_profile *profile) {
    struct ksu_set_app_profile_cmd cmd = {};
    cmd.profile = *profile;
    return ksuctl(KSU_IOCTL_SET_APP_PROFILE, &cmd) == 0;
}

int get_app_profile(app_profile *profile) {
    struct ksu_get_app_profile_cmd cmd = {.profile = *profile};
    int ret = ksuctl(KSU_IOCTL_GET_APP_PROFILE, &cmd);
    *profile = cmd.profile;
    return ret;
}

bool set_exec_policy(uid_t uid, bool restricted, const std::vector<std::string> &paths) {
    struct ksu_exec_policy_cmd cmd = {};
    cmd.uid = uid;
    cmd.restricted = restricted && !paths.empty();
    cmd.count = 0;
    for (const auto &path : paths) {
        if (cmd.count >= KSU_EXEC_POLICY_MAX)
            break;
        if (path.empty())
            continue;
        // 截断到 KSU_EXEC_POLICY_PATH-1，保证 NUL 终止。
        size_t n = std::min(path.size(), (size_t)(KSU_EXEC_POLICY_PATH - 1));
        memcpy(cmd.paths[cmd.count], path.data(), n);
        cmd.paths[cmd.count][n] = '\0';
        cmd.count++;
    }
    return ksuctl(KSU_IOCTL_SET_EXEC_POLICY, &cmd) == 0;
}

std::vector<std::string> get_exec_policy(uid_t uid, bool &restricted) {
    std::vector<std::string> out;
    struct ksu_exec_policy_cmd cmd = {};
    cmd.uid = uid;
    if (ksuctl(KSU_IOCTL_GET_EXEC_POLICY, &cmd) != 0) {
        restricted = false;
        return out;
    }
    restricted = cmd.restricted;
    for (unsigned i = 0; i < cmd.count && i < KSU_EXEC_POLICY_MAX; i++) {
        out.emplace_back((const char *) cmd.paths[i]);
    }
    return out;
}

bool set_superkey(const std::string &new_key, const std::string &old_key, bool clear) {
    struct ksu_superkey_cmd cmd = {};
    snprintf((char *) cmd.new_key, sizeof(cmd.new_key), "%s", new_key.c_str());
    snprintf((char *) cmd.old_key, sizeof(cmd.old_key), "%s", old_key.c_str());
    cmd.has_old = !old_key.empty();
    cmd.clear = clear;
    return ksuctl(KSU_IOCTL_SET_SUPERKEY, &cmd) == 0;
}

bool superkey_auth(const std::string &key) {
    struct ksu_superkey_auth_cmd cmd = {};
    snprintf((char *) cmd.key, sizeof(cmd.key), "%s", key.c_str());
    return ksuctl(KSU_IOCTL_SUPERKEY_AUTH, &cmd) == 0;
}

bool set_superkey_guard(bool armed) {
    struct ksu_set_feature_cmd cmd = {};
    cmd.feature_id = KSU_FEATURE_SUPERKEY_GUARD;
    cmd.value = armed ? 1 : 0;
    return ksuctl(KSU_IOCTL_SET_FEATURE, &cmd) == 0;
}

int superkey_status_query(bool &set, bool &guard_armed) {
    struct ksu_superkey_status_cmd cmd = {};
    int rc = ksuctl(KSU_IOCTL_SUPERKEY_STATUS, &cmd);
    set = cmd.set != 0;
    guard_armed = cmd.guard_armed != 0;
    if (rc == 0) {
        return 0;
    }
    // Fail closed: report the error instead of pretending nothing is set.
    set = false;
    guard_armed = false;
    return rc < 0 ? -rc : rc;
}

bool set_uts_spoof(uid_t uid, bool enabled, const std::string &release, const std::string &version) {
    struct ksu_uts_spoof_cmd cmd = {};
    cmd.uid = uid;
    cmd.enabled = enabled;
    snprintf((char *) cmd.release, sizeof(cmd.release), "%s", release.c_str());
    snprintf((char *) cmd.version, sizeof(cmd.version), "%s", version.c_str());
    return ksuctl(KSU_IOCTL_SET_UTS_SPOOF, &cmd) == 0;
}

int get_uts_spoof(uid_t uid, std::string &release, std::string &version) {
    struct ksu_uts_spoof_cmd cmd = {};
    cmd.uid = uid;
    int ret = ksuctl(KSU_IOCTL_GET_UTS_SPOOF, &cmd);
    if (ret != 0)
        return -1;
    if (!cmd.enabled)
        return 0;
    release = (const char *) cmd.release;
    version = (const char *) cmd.version;
    return 1;
}

bool set_su_enabled(bool enabled) {
    struct ksu_set_feature_cmd cmd = {};
    cmd.feature_id = KSU_FEATURE_SU_COMPAT;
    cmd.value = enabled ? 1 : 0;
    return ksuctl(KSU_IOCTL_SET_FEATURE, &cmd) == 0;
}

bool is_su_enabled() {
    struct ksu_get_feature_cmd cmd = {};
    cmd.feature_id = KSU_FEATURE_SU_COMPAT;
    if (ksuctl(KSU_IOCTL_GET_FEATURE, &cmd) != 0) {
        return false;
    }
    if (!cmd.supported) {
        return false;
    }
    return cmd.value != 0;
}

static inline bool get_feature(uint32_t feature_id, uint64_t *out_value, bool *out_supported) {
    struct ksu_get_feature_cmd cmd = {};
    cmd.feature_id = feature_id;
    if (ksuctl(KSU_IOCTL_GET_FEATURE, &cmd) != 0) {
        return false;
    }
    if (out_value) *out_value = cmd.value;
    if (out_supported) *out_supported = cmd.supported;
    return true;
}

static inline bool set_feature(uint32_t feature_id, uint64_t value) {
    struct ksu_set_feature_cmd cmd = {};
    cmd.feature_id = feature_id;
    cmd.value = value;
    return ksuctl(KSU_IOCTL_SET_FEATURE, &cmd) == 0;
}

bool set_kernel_umount_enabled(bool enabled) {
    return set_feature(KSU_FEATURE_KERNEL_UMOUNT, enabled ? 1 : 0);
}

bool is_kernel_umount_enabled() {
    uint64_t value = 0;
    bool supported = false;
    if (!get_feature(KSU_FEATURE_KERNEL_UMOUNT, &value, &supported)) {
        return false;
    }
    if (!supported) {
        return false;
    }
    return value != 0;
}

int set_selinux_hide_enabled(bool enabled) {
    if (!set_feature(KSU_FEATURE_SELINUX_HIDE, enabled ? 1 : 0)) {
        return -errno;
    }
    return 0;
}

bool is_selinux_hide_enabled() {
    uint64_t value = 0;
    bool supported = false;
    if (!get_feature(KSU_FEATURE_SELINUX_HIDE, &value, &supported)) {
        return false;
    }
    if (!supported) {
        return false;
    }
    return value != 0;
}
