#pragma once
#include <string>
#include <vector>

inline std::vector<std::string> mapped_arguments(const std::string& proot, const std::string& root,
                                                  const std::string& cwd,
                                                  const std::vector<std::string>& command) {
    return [&]() {
        // PRoot observes path-bearing syscalls, including direct syscalls and descendants.
        // Both Android aliases resolve to the same app-private runtime; no global mount is changed.
        std::vector<std::string> result {proot, "--kill-on-exit", "-L", "-r", root + "/rootfs",
            "-b", "/system", "-b", "/apex", "-b", "/proc", "-b", "/dev",
            "-b", root + "/files:/data/data/com.termux/files",
            "-b", root + "/files:/data/user/0/com.termux/files",
            "-b", root + "/cache:/data/data/com.termux/cache",
            "-b", root + "/cache:/data/user/0/com.termux/cache",
            "-b", root + "/files/usr/bin:/bin",
            "-b", root + "/files/usr:/usr",
            "-b", root + "/files/usr/tmp:/tmp",
            "-w", cwd};
        result.insert(result.end(), command.begin(), command.end());
        return result;
    }();
}
