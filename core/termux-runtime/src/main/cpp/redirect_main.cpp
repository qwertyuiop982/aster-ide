#include "redirect_arguments.h"
#include <cerrno>
#include <cstdio>
#include <unistd.h>

int main(int argc, char** argv) {
    if (argc < 6) {
        std::fprintf(stderr, "Usage: aster_redirect PROOT PRIVATE_FILES GUEST_CWD -- COMMAND [ARGS...]\n");
        return 2;
    }
    if (std::string(argv[4]) != "--") return 2;
    std::vector<std::string> command;
    for (int i = 5; i < argc; ++i) command.emplace_back(argv[i]);
    auto args = mapped_arguments(argv[1], argv[2], argv[3], command);
    std::vector<char*> values;
    for (auto& value : args) values.push_back(value.data());
    values.push_back(nullptr);
    execv(values[0], values.data());
    std::perror("aster_redirect");
    return errno == ENOENT ? 127 : 126;
}