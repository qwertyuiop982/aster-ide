#!/usr/bin/env python3
"""Python port of the decrypted idesetup installer.

Download sources and the Android/Bionic installation flow are preserved.
Paths use HOME, PREFIX, SYSROOT, JAVA_HOME and ANDROID_ROOT instead of an
application-specific /data/data/... prefix. GitHub URLs automatically use
https://gh-proxy.org/<url>. NDK metadata still uses the original acs command;
files use the shared proxy downloader and the original bundle extractor.

Examples:
    python3 idesetup.py -s 34.0.4 -j 17
    python3 idesetup.py -s 35.0.1 -j 21 -n 28.2.13676358 -y
    python3 idesetup.py --help

The original -c switch disables command-line tools despite its long name.
The original -wn switch is retained; -n selects whether an NDK is installed.
"""
import argparse
import hashlib
import json
import os
import platform
import re
import shlex
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


GITHUB_PROXY = "https://gh-proxy.org/"
SDK_MANIFEST = (
    "https://raw.githubusercontent.com/AndroidIDE-Rv2Official/"
    "androidide-rv2-tools/main/manifest.json"
)
NDK_MANIFEST = (
    "https://raw.githubusercontent.com/AndroidCSOfficial/"
    "acs-build-system/refs/heads/main/acs-manifest.json"
)
NDK_TOKEN = "795203ab38cefaa8663642d85fdb22bb8c7fc0e10d079da3209adec3ce39e0e3"


class SetupError(Exception):
    pass


def github_url(url):
    """Prefix GitHub URLs; leave other and already proxied URLs unchanged."""
    parsed = urllib.parse.urlsplit(url)
    hostname = (parsed.hostname or "").lower()
    if parsed.scheme in ("http", "https") and (
        hostname == "github.com"
        or hostname.endswith(".github.com")
        or hostname == "githubusercontent.com"
        or hostname.endswith(".githubusercontent.com")
    ):
        return GITHUB_PROXY + url
    return url


def verify_sha256(path, expected):
    if re.fullmatch(r"[0-9a-fA-F]{64}", expected) is None:
        raise SetupError("Invalid NDK SHA256 checksum in the manifest.")
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    if digest.hexdigest() != expected.lower():
        raise SetupError("NDK SHA256 verification failed: {}".format(path))



def print_info(message):
    print("[ INFO ] " + str(message), flush=True)


def print_warn(message):
    print("[ WARNING ] " + str(message), flush=True)


def print_success(message):
    print("[ SUCCESS ] " + str(message), flush=True)


def npr(message):
    print("[ NDK SETUP ] " + str(message), flush=True)


def run(command, cwd=None, check=True, capture=False):
    return subprocess.run(
        [str(part) for part in command],
        cwd=str(cwd) if cwd is not None else None,
        check=check,
        text=True,
        stdout=subprocess.PIPE if capture else None,
    )


def is_yes(message, assume_yes):
    prompt = message + " ([y]es/[n]o): "
    if assume_yes:
        print(prompt + "y", flush=True)
        return True
    try:
        answer = input(prompt)
    except EOFError:
        return False
    return re.fullmatch(r"[Yy][Ee]?[Ss]?", answer) is not None


def check_command_exists(command):
    if shutil.which(command) is None:
        raise SetupError("Command {!r} not found!".format(command))


def environment_path(name, fallback=None):
    value = os.environ.get(name)
    if value:
        return Path(value).expanduser()
    if fallback is not None:
        return Path(fallback).expanduser()
    raise SetupError("Environment variable {} is not set.".format(name))


def grep_prop(name):
    android_root = os.environ.get("ANDROID_ROOT")
    if not android_root:
        return ""
    try:
        lines = (Path(android_root) / "build.prop").read_text(
            encoding="utf-8", errors="replace"
        ).splitlines()
    except OSError:
        return ""
    for line in lines:
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip()
    return ""


def grep_get_prop(name):
    value = grep_prop(name)
    if value:
        return value
    getprop = shutil.which("getprop")
    if getprop is None:
        return ""
    result = run([getprop, name], check=False, capture=True)
    return result.stdout.strip() if result.returncode == 0 else ""


def api_level_arch_detect():
    api = grep_get_prop("ro.build.version.sdk")
    abi = grep_get_prop("ro.product.cpu.abi")
    architectures = {
        "arm64-v8a": ("arm64", "armeabi-v7a", True),
        "x86_64": ("x64", "x86", True),
        "armeabi-v7a": ("arm", "armeabi-v7a", False),
        "x86": ("x86", "x86", False),
        "riscv64": ("riscv64", "riscv32", True),
    }
    if abi not in architectures:
        raise SetupError("Unsupported or unavailable device ABI: {!r}".format(abi))
    arch, abi32, is64bit = architectures[abi]
    return api, abi, arch, abi32, is64bit


def install_packages(options, package_manager, packages):
    command = package_manager + ["install"] + list(packages)
    if options.assume_yes:
        command.append("-y")
    run(command)


def download_file(url, destination):
    if not isinstance(url, str) or not url.strip():
        raise SetupError("The manifest does not contain a download URL.")
    url = github_url(url)
    destination = Path(destination)
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "idesetup-python"})
    with urllib.request.urlopen(request, timeout=60) as response:
        with destination.open("wb") as output:
            shutil.copyfileobj(response, output, length=1024 * 1024)


def download_and_extract(options, name, url, directory, destination, extract_with="xz"):
    directory = Path(directory)
    destination = Path(destination)
    directory.mkdir(parents=True, exist_ok=True)
    do_download = True
    if destination.is_file():
        print_info("File {} already exists.".format(destination.name))
        if is_yes("Do you want to skip the download process?", options.assume_yes):
            do_download = False
        print()
    if do_download:
        print_info("Downloading {}...".format(name))
        download_file(url, destination)
        print_success("{} has been downloaded.".format(name))
        print()
    if not destination.is_file():
        raise SetupError("The downloaded file {} does not exist.".format(destination))
    if extract_with == "xz":
        print_info("Extracting downloaded archive with xz...")
        run(["tar", "xvJf", destination], cwd=directory)
    else:
        print_info("Extracting downloaded archive with unzip...")
        run(["unzip", destination], cwd=directory)
    print_info("Extracted successfully")
    destination.unlink()
    print()


def download_comp(options, manifest, name, keys, directory, archive_name):
    print_info("Extracting URL for {} from manifest...".format(name))
    value = manifest
    try:
        for key in keys:
            value = value[key]
    except (KeyError, TypeError):
        raise SetupError("Manifest entry not found: {}".format(".".join(keys)))
    if not isinstance(value, str) or not value:
        raise SetupError("Empty download URL: {}".format(".".join(keys)))
    print_success("Found URL: " + value)
    print()
    download_and_extract(
        options, name, value, directory,
        Path(directory) / (archive_name + ".tar.xz"), "xz"
    )


def ensure_ndk(home, ndk_version):
    return (home / "android-sdk" / "ndk" / ndk_version / "ndk-build").is_file()


def setup_ndk_extractor(options, home, prefix):
    _, abi, _, _, _ = api_level_arch_detect()
    if options.ndk == "0":
        npr("NDK installation skipped.")
        return
    check_command_exists("acs")
    command = [
        "acs", "-r", github_url(NDK_MANIFEST), "-a", abi,
        "-i", "android-native-kit", "-v", options.ndk,
    ]
    filename = run(command + ["-f", "filename"], capture=True).stdout.strip()
    version = run(command + ["-f", "version"], capture=True).stdout.strip()
    checksum = run(command + ["-f", "sha256"], capture=True).stdout.strip()
    url = run(command + ["-f", "url"], capture=True).stdout.strip()
    if not filename:
        raise SetupError("The NDK manifest did not provide an artifact filename.")
    print()
    npr("- Native Development Kit Manager -")
    npr("Device ABI: " + abi)
    npr("Artifact name: " + filename)
    npr("NDK version: " + version)
    npr("NDK SHA256: " + checksum[:8] + "...")
    print()
    npr("Downloading " + filename + "...")
    # acs --download uses the original URL embedded in the manifest. Download
    # through the shared Python helper so the actual NDK file uses the proxy.
    source = home / "acs" / filename
    download_file(url, source)
    verify_sha256(source, checksum)
    output = prefix / "bin" / "ndk_extractor"
    shutil.move(str(source), str(output))
    npr("Setting up token chain...")
    output.chmod(output.stat().st_mode | 0o111)
    npr("Every operation succeeded")


def ndk_extractor(home, prefix, arch):
    destination = home / "android-sdk" / "ndk"
    destination.mkdir(parents=True, exist_ok=True)
    run([
        prefix / "bin" / "ndk_extractor", "--extract", NDK_TOKEN,
        arch, destination, "--decompress",
    ])


def setup_ndk(options, home, prefix):
    npr("Running environmental check...")
    if not (prefix / "bin" / "ndk_extractor").is_file():
        setup_ndk_extractor(options, home, prefix)
    if options.ndk == "0":
        return
    _, _, arch, _, _ = api_level_arch_detect()
    npr("Checking device architecture...")
    if arch in ("x86", "riscv64"):
        raise SetupError("Unsupported architecture: " + arch)
    npr("Device architecture: " + arch)
    if ensure_ndk(home, options.ndk):
        npr("NDK {} already installed! Setup skipped.".format(options.ndk))
        return
    ndk_extractor(home, prefix, arch)
    npr("Successfully extracted")
    npr("Applying NDK patches...")
    # Keep the patch version used by the original script.
    ndk_version = "28.2.13676358"
    wrapper = home / "android-sdk" / "ndk" / ndk_version / "ndk-build"
    shell = os.environ.get("SHELL") or str(prefix / "bin" / "sh")
    wrapper.write_text(
        "#!" + shell + "\n"
        'DIR=$(cd "$(dirname "$0")" && pwd)\n'
        'exec "${PREFIX:?PREFIX is not set}/bin/bash" "$DIR/build/ndk-build" "$@"\n',
        encoding="utf-8",
    )
    npr("Successfully applied NDK patches")


def parse_arguments():
    parser = argparse.ArgumentParser(
        description="AndroidIDE build tools installer (Python port).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "Environment variables:\n"
            "  HOME          Default installation directory and NDK download directory.\n"
            "  PREFIX        Runtime prefix containing bin/bash and bin/ndk_extractor.\n"
            "  SYSROOT       Root containing etc/ide-environment.properties.\n"
            "                Defaults to PREFIX when unset.\n"
            "  JAVA_HOME     JDK path written to ide-environment.properties.\n"
            "  ANDROID_ROOT  Android system root used to read build.prop.\n\n"
            "Downloads use the original Android/Bionic sources.\n"
            "GitHub URLs automatically use https://gh-proxy.org/<url>.\n"
            "NDK metadata uses acs; files use the shared proxy downloader.\n"
            "NDK installation retains the original bundle extractor.\n"
            "Examples:\n"
            "  python3 idesetup.py -s 34.0.4 -j 17\n"
            "  python3 idesetup.py -s 35.0.1 -j 21 -n 28.2.13676358 -y"
        ),
    )
    parser.add_argument("-i", "--install-dir", default=os.environ.get("HOME", str(Path.home())),
                        help="Installation directory (default: HOME).")
    parser.add_argument("-s", "--sdk", default="34.0.4",
                        help="SDK build-tools version (default: 34.0.4).")
    parser.add_argument("-c", "--with-cmdline-tools", dest="with_cmdline", action="store_false",
                        default=True, help="Skip command-line tools, matching the original switch behavior.")
    parser.add_argument("-m", "--manifest", default=SDK_MANIFEST,
                        help="SDK manifest URL (default: original RV2 tools manifest).")
    parser.add_argument("-j", "--jdk", choices=("17", "21"), default="17",
                        help="OpenJDK version (default: 17).")
    parser.add_argument("-g", "--with-git", action="store_true", help="Install git.")
    parser.add_argument("-o", "--with-openssh", action="store_true", help="Install openssh.")
    parser.add_argument("-wn", "--with-ndk", action="store_true",
                        help="Retain the original NDK switch; select a version using -n.")
    parser.add_argument("-n", "--ndk", default="0", help="NDK version (default: 0, skip NDK).")
    parser.add_argument("-y", "--assume-yes", action="store_true", help="Answer yes to all prompts.")
    parser.add_argument("-a", "--arch", default=platform.machine(),
                        help="SDK CPU architecture (default: uname -m).")
    parser.add_argument("-p", "--package-manager", default="pkg",
                        help="Package manager command (default: pkg).")
    parser.add_argument("-l", "--curl", default="libcurl",
                        help="Curl package name (default: libcurl).")
    return parser.parse_args()


def main():
    options = parse_arguments()
    home = environment_path("HOME", Path.home()).absolute()
    install_dir = Path(options.install_dir).expanduser().absolute()
    prefix = environment_path("PREFIX").absolute()
    sysroot = environment_path("SYSROOT", prefix).absolute()
    package_manager = shlex.split(options.package_manager)
    if not package_manager:
        raise SetupError("The package manager command is empty.")
    check_command_exists(package_manager[0])
    arch = "arm" if options.arch in ("armv7l", "armv8l") else options.arch

    # Preserve the original preliminary apt repair step.
    apt = shutil.which("apt")
    if apt is not None:
        result = run([apt, "update", "-y"], check=False)
        if result.returncode == 0:
            run([apt, "--fix-broken", "install", "-y"], check=False)

    if options.jdk == "21":
        print_warn("OpenJDK 21 support in AndroidIDE is experimental.")
        print_warn("Running Gradle on JDK 21 requires Gradle 8.5 or newer.")
        if not is_yes("Do you still want to install OpenJDK 21?", options.assume_yes):
            options.jdk = "17"
            print_info("OpenJDK version has been reset to '17'")

    sdk_version = "_" + options.sdk.replace(".", "_")
    packages = ["jq", "tar", "wget"]
    if options.with_git:
        packages.append("git")
    if options.with_openssh:
        packages.append("openssh")
    packages.append(options.curl)

    print("------------------------------------------")
    print("Installation directory    : " + str(install_dir))
    print("SDK version               : " + options.sdk)
    print("JDK version               : " + options.jdk)
    print("With command line tools   : " + str(options.with_cmdline).lower())
    print("Extra packages            : " + " ".join(packages))
    print("CPU architecture          : " + arch)
    print("------------------------------------------")
    if not is_yes("Confirm configuration", options.assume_yes):
        raise SetupError("Aborting...")
    install_dir.mkdir(parents=True, exist_ok=True)

    print_info("Update packages...")
    run(package_manager + ["update"])
    upgrade = package_manager + ["upgrade"]
    if options.assume_yes:
        upgrade.append("-y")
    run(upgrade)
    print_info("Installing required packages...")
    install_packages(options, package_manager, packages)
    print_success("Packages installed")
    print()

    print_info("Downloading manifest file...")
    downloaded_manifest = install_dir / "manifest.json"
    download_file(options.manifest, downloaded_manifest)
    print_success("Manifest file downloaded")
    with downloaded_manifest.open(encoding="utf-8") as source:
        manifest = json.load(source)
    print()

    download_comp(options, manifest, "Android SDK", ["android_sdk"], install_dir, "android-sdk")
    sdk_dir = install_dir / "android-sdk"
    download_comp(options, manifest, "Android SDK Build Tools",
                  ["build_tools", arch, sdk_version], sdk_dir, "android-sdk-build-tools")
    download_comp(options, manifest, "Android SDK Platform Tools",
                  ["platform_tools", arch, sdk_version], sdk_dir, "android-sdk-platform-tools")
    if options.with_cmdline:
        download_comp(options, manifest, "Command-line tools", ["cmdline_tools"], sdk_dir, "cmdline-tools")

    print_info("Installing package: 'openjdk-{}'".format(options.jdk))
    install_packages(options, package_manager, ["openjdk-" + options.jdk])
    print_info("JDK {} has been installed.".format(options.jdk))
    java_home = os.environ.get("JAVA_HOME", "")
    print_info("NDK " + options.ndk)
    print_info("Updating ide-environment.properties...")
    print_info("JAVA_HOME=" + java_home)
    print()
    properties_dir = sysroot / "etc"
    properties = properties_dir / "ide-environment.properties"
    properties_dir.mkdir(parents=True, exist_ok=True)
    if not properties.exists() or is_yes(
        str(properties) + " already exists. Would you like to overwrite it?", options.assume_yes
    ):
        properties.write_text("JAVA_HOME=" + java_home, encoding="utf-8")
        print_success("Properties file updated successfully!")
    else:
        print_warn("Manually edit {} and set JAVA_HOME and ANDROID_SDK_ROOT.".format(properties))

    setup_ndk(options, home, prefix)
    downloaded_manifest.unlink()
    print_success("Downloads completed. You are ready to go!")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n[ ERROR ] Installation interrupted.", file=sys.stderr)
        sys.exit(130)
    except (SetupError, OSError, subprocess.CalledProcessError,
            urllib.error.URLError, json.JSONDecodeError) as error:
        print("[ ERROR ] " + str(error), file=sys.stderr)
        sys.exit(1)
