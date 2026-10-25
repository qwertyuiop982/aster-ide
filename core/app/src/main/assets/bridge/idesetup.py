#!/usr/bin/env python3
"""Python port of the decrypted idesetup installer.

Download sources and the Android/Bionic installation flow are preserved.
Paths use HOME, PREFIX, SYSROOT, JAVA_HOME and ANDROID_ROOT instead of an
application-specific /data/data/... prefix. GitHub URLs automatically use
https://gh-proxy.org/<url>. NDK metadata is read directly from the original ACS manifest;
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
import tarfile
import tempfile
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
    destination = Path(destination).expanduser().absolute()
    destination.parent.mkdir(parents=True, exist_ok=True)
    partial = destination.with_name(destination.name + ".part")
    control = partial.with_name(partial.name + ".aria2")
    fallback = destination.with_name(destination.name + ".fallback.part")

    def complete(file):
        if not file.is_file() or file.stat().st_size == 0:
            return False
        if file.stat().st_size > 3 * 1024 * 1024 * 1024:
            raise SetupError("Toolchain archive exceeds 3 GiB")
        file.replace(destination)
        for remaining in (partial, control, fallback):
            remaining.unlink(missing_ok=True)
        return True

    aria2 = shutil.which("aria2c")
    if aria2:
        print_info("Downloading with aria2c (16 connections, resume enabled)...")
        command = [aria2, "-x16", "-s16", "-c", "--max-tries=0",
                   "--auto-file-renaming=false", "--allow-overwrite=true",
                   "--file-allocation=none", "--connect-timeout=30", "--timeout=60",
                   "--retry-wait=5", "--max-file-not-found=1",
                   "-d", str(destination.parent), "-o", partial.name, url]
        try:
            result = run(command, check=False)
        except OSError as error:
            print_warn("aria2c could not start: " + str(error))
        else:
            if result.returncode == 0 and not control.exists() and complete(partial):
                return
        print_warn("aria2c failed; trying wget...")

    # Segmented files may have holes; only sequential data can resume by length.
    sequential = fallback if control.exists() else partial
    wget = shutil.which("wget")
    if wget:
        try:
            result = run([wget, "-c", "--timeout=30", "--tries=3", "-O", str(sequential), url], check=False)
        except OSError as error:
            print_warn("wget could not start: " + str(error))
        else:
            if result.returncode == 0 and complete(sequential):
                return
        print_warn("wget failed; trying urllib...")

    offset = sequential.stat().st_size if sequential.is_file() else 0
    headers = {"User-Agent": "idesetup-python", "Accept-Encoding": "identity"}
    if offset:
        headers["Range"] = "bytes={}-".format(offset)
    request = urllib.request.Request(url, headers=headers)
    try:
        response = urllib.request.urlopen(request, timeout=60)
    except urllib.error.HTTPError as error:
        extent = re.fullmatch(r"bytes \*/(\d+)", error.headers.get("Content-Range", ""))
        if error.code == 416 and extent and int(extent.group(1)) == offset and complete(sequential):
            return
        raise
    with response:
        resumed = response.status == 206
        length = response.headers.get("Content-Length")
        expected = int(length) if length is not None else None
        if resumed:
            extent = re.fullmatch(r"bytes (\d+)-(\d+)/(\d+|\*)", response.headers.get("Content-Range", ""))
            if not extent or int(extent.group(1)) != offset or int(extent.group(2)) < offset:
                raise SetupError("Invalid HTTP resume range")
            expected = int(extent.group(2)) - offset + 1
            if extent.group(3) != "*" and int(extent.group(2)) + 1 != int(extent.group(3)):
                raise SetupError("Incomplete HTTP resume range")
        elif response.status != 200:
            raise SetupError("Unexpected download HTTP status: " + str(response.status))
        total = offset if resumed else 0
        if expected is not None and total + expected > 3 * 1024 * 1024 * 1024:
            raise SetupError("Toolchain archive exceeds 3 GiB")
        received = 0
        with sequential.open("ab" if resumed else "wb") as output:
            while True:
                chunk = response.read(256 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                received += len(chunk)
                if total > 3 * 1024 * 1024 * 1024:
                    raise SetupError("Toolchain archive exceeds 3 GiB")
                output.write(chunk)
        if expected is not None and received != expected:
            raise SetupError("Download interrupted; partial file retained for resume")
    if not complete(sequential):
        raise SetupError("Downloaded file is empty")


def cached_download(url, destination, checksum, alternate=None):
    destination = Path(destination)
    if re.fullmatch(r"[0-9a-fA-F]{64}", checksum) is None:
        raise SetupError("Invalid NDK SHA256 checksum in the manifest.")
    if destination.is_file():
        try:
            verify_sha256(destination, checksum)
        except SetupError:
            print_warn("Cached NDK checksum differs; downloading again")
            destination.unlink()
            for suffix in (".part", ".part.aria2", ".fallback.part"):
                destination.with_name(destination.name + suffix).unlink(missing_ok=True)
        else:
            print_info("Reusing verified NDK cache: " + str(destination))
            return destination
    if alternate is not None and Path(alternate).is_file():
        try:
            verify_sha256(alternate, checksum)
        except SetupError:
            pass
        else:
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(alternate, destination)
            print_info("Reusing previous NDK cache: " + str(alternate))
            return destination
    download_file(url, destination)
    verify_sha256(destination, checksum)
    return destination


def read_manifest(url):
    request = urllib.request.Request(github_url(url), headers={"User-Agent": "Aster-idesetup/0.1.4"})
    with urllib.request.urlopen(request, timeout=45) as response:
        body = response.read(2 * 1024 * 1024 + 1)
    if len(body) > 2 * 1024 * 1024:
        raise SetupError("Toolchain manifest is too large")
    value = json.loads(body)
    if not isinstance(value, dict):
        raise SetupError("Invalid toolchain manifest")
    return value


def extract_tar(archive, destination):
    destination = Path(destination)
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as source:
        source.extractall(destination, filter="data")


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
        extract_tar(destination, directory)
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
    cache = getattr(options, "download_dir", None)
    archive_dir = Path(cache) if cache else Path(directory)
    if cache:
        archive_name += "-" + hashlib.sha256(value.encode()).hexdigest()[:16]
    download_and_extract(
        options, name, value, directory,
        archive_dir / (archive_name + ".tar.xz"), "xz"
    )


def ndk_root(options, home):
    sdk = getattr(options, "sdk_dir", None)
    return (Path(sdk) if sdk is not None else home / "android-sdk") / "ndk"


def ensure_ndk(home, ndk_version, options=None):
    root = ndk_root(options, home) if options is not None else home / "android-sdk" / "ndk"
    return (root / ndk_version / "ndk-build").is_file()


def ndk_revision(version):
    # ACS r29 manifest keeps its beta release identifier; the archive is final r29.
    return "29.0.14206865" if version == "29.0.14033849" else version


def select_ndk(manifest, abi, version):
    matches = [package for package in manifest.get("packages", [])
               if package.get("id") == "android-native-kit" and package.get("architecture") == abi and ndk_revision(package.get("version")) == ndk_revision(version)]
    if len(matches) != 1:
        raise SetupError("No Android/Bionic NDK {} for {}".format(version, abi))
    package = matches[0]
    for key in ("filename", "version", "sha256", "url"):
        if not isinstance(package.get(key), str) or not package[key]:
            raise SetupError("NDK manifest is missing " + key)
    if Path(package["filename"]).name != package["filename"]:
        raise SetupError("Unsafe NDK artifact filename")
    if re.fullmatch(r"[0-9a-fA-F]{64}", package["sha256"]) is None:
        raise SetupError("Invalid NDK SHA256 checksum")
    return package


def setup_ndk_extractor(options, home, prefix):
    _, abi, _, _, _ = api_level_arch_detect()
    package = select_ndk(read_manifest(getattr(options, "ndk_manifest", NDK_MANIFEST)), abi, options.ndk)
    npr("Downloading Android/Bionic NDK " + package["version"])
    npr("Device ABI: " + abi)
    source = cached_download(package["url"], home / "acs" / package["filename"], package["sha256"],
                             alternate=home / ".aster/toolchain-downloads" / package["filename"])
    if package.get("encrypted", False):
        output = prefix / "bin/ndk_extractor"
        output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(source, output)
        output.chmod(0o700)
        from toolchain_native import android_elf
        android_elf(output)
    return package, source


def ndk_extractor(home, prefix, arch, options=None):
    destination = ndk_root(options, home) if options is not None else home / "android-sdk/ndk"
    destination.mkdir(parents=True, exist_ok=True)
    run([prefix / "bin/ndk_extractor", "--extract", NDK_TOKEN, arch, destination, "--decompress"])


def patch_ndk(options, home, prefix):
    destination = ndk_root(options, home) / options.ndk
    if not destination.is_dir():
        raise SetupError("NDK extractor did not create " + str(destination))
    properties = destination / "source.properties"
    if not properties.is_file():
        raise SetupError("NDK has no source.properties")
    revision = re.search(r"^Pkg.Revision\s*=\s*(.+)$", properties.read_text(), re.MULTILINE)
    if revision is None or revision.group(1).strip() != options.ndk:
        raise SetupError("NDK revision differs from the requested version")
    wrapper = destination / "ndk-build"
    shell = str(prefix / "bin/bash")
    if not (destination / "build/ndk-build").is_file():
        raise SetupError("NDK ndk-build script is missing")
    wrapper.write_text("#!" + shell + "\nDIR=$(cd \"$(dirname \"$0\")\" && pwd)\nexec \"" + shell + "\" \"$DIR/build/ndk-build\" \"$@\"\n")
    wrapper.chmod(0o700)
    from toolchain_native import ndk_binaries
    ndk_binaries(destination)
    return destination


def setup_ndk(options, home, prefix):
    if options.ndk == "0":
        npr("NDK installation skipped.")
        return None
    from toolchain_native import ndk_binaries
    options.ndk = ndk_revision(options.ndk)
    destination = ndk_root(options, home) / options.ndk
    if ensure_ndk(home, options.ndk, options):
        try:
            ndk_binaries(destination)
            npr("Android/Bionic NDK already installed: " + options.ndk)
            return destination
        except (ValueError, OSError):
            npr("Replacing incompatible NDK host executables")
    _, _, arch, _, _ = api_level_arch_detect()
    package, archive = setup_ndk_extractor(options, home, prefix)
    if package.get("encrypted", False):
        ndk_extractor(home, prefix, arch, options)
    else:
        parent = ndk_root(options, home)
        parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix=".aster-ndk-", dir=parent) as temporary:
            unpacked = Path(temporary)
            extract_tar(archive, unpacked)
            candidates = [file.parent for file in unpacked.rglob("source.properties") if (file.parent / "toolchains/llvm").is_dir()]
            if len(candidates) != 1:
                raise SetupError("NDK archive layout is invalid")
            candidate = candidates[0]
            ndk_binaries(candidate)
            revision = re.search(r"^Pkg.Revision\s*=\s*(.+)$", (candidate / "source.properties").read_text(), re.MULTILINE)
            if revision is None or revision.group(1).strip() != options.ndk:
                raise SetupError("NDK archive revision mismatch")
            backup = destination.with_name(destination.name + ".aster-backup")
            if destination.exists():
                if backup.exists():
                    shutil.rmtree(backup)
                destination.rename(backup)
            try:
                candidate.rename(destination)
                patch_ndk(options, home, prefix)
            except Exception:
                if destination.exists():
                    shutil.rmtree(destination)
                if backup.exists():
                    backup.rename(destination)
                raise
            else:
                if backup.exists():
                    shutil.rmtree(backup)
    result = patch_ndk(options, home, prefix)
    npr("Android/Bionic NDK ready: " + str(result))
    return result


def parse_arguments(argv=None):
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
            "NDK metadata uses the ACS manifest; files use the shared proxy downloader.\n"
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
    parser.add_argument("--sdk-root", help="SDK directory used by the embedded application")
    parser.add_argument("--ndk-manifest", default=NDK_MANIFEST, help="Android-host NDK manifest URL")
    parser.add_argument("--managed-runtime", action="store_true", help="Use the application-managed package update flow")
    return parser.parse_args(argv)


def main(argv=None):
    options = parse_arguments(argv)
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
    if apt is not None and not options.managed_runtime:
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
    packages = ["jq", "tar", "wget", "aria2"]
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

    if not options.managed_runtime:
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

    sdk_dir = Path(options.sdk_root).expanduser().absolute() if options.sdk_root else install_dir / "android-sdk"
    options.sdk_dir = sdk_dir
    options.download_dir = home / "acs/sdk-downloads"
    sdk_dir.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".aster-sdk-", dir=sdk_dir.parent) as temporary:
        temporary = Path(temporary)
        download_comp(options, manifest, "Android SDK", ["android_sdk"], temporary, "android-sdk")
        unpacked = temporary / "android-sdk"
        if not unpacked.is_dir():
            raise SetupError("Android SDK archive layout is invalid")
        download_comp(options, manifest, "Android SDK Build Tools",
                      ["build_tools", arch, sdk_version], unpacked, "android-sdk-build-tools")
        download_comp(options, manifest, "Android SDK Platform Tools",
                      ["platform_tools", arch, sdk_version], unpacked, "android-sdk-platform-tools")
        if options.with_cmdline:
            download_comp(options, manifest, "Command-line tools", ["cmdline_tools"], unpacked, "cmdline-tools")
        from toolchain_native import sdk_binaries
        sdk_binaries(unpacked, options.sdk)
        from android_platform import ensure_platform
        ensure_platform(unpacked, 35)
        shutil.copytree(unpacked, sdk_dir, dirs_exist_ok=True, symlinks=True)

    print_info("Installing package: 'openjdk-{}'".format(options.jdk))
    install_packages(options, package_manager, ["openjdk-" + options.jdk])
    print_info("JDK {} has been installed.".format(options.jdk))
    java_home = prefix / "lib/jvm" / ("java-" + options.jdk + "-openjdk")
    if not (java_home / "bin/java").is_file():
        raise SetupError("The selected Android JDK is missing: " + str(java_home))
    from toolchain_native import android_elf
    android_elf(java_home / "bin/java")
    print_info("NDK " + options.ndk)
    print_info("Updating ide-environment.properties...")
    print_info("JAVA_HOME=" + str(java_home))
    print()
    properties_dir = sysroot / "etc"
    properties = properties_dir / "ide-environment.properties"
    properties_dir.mkdir(parents=True, exist_ok=True)
    if not properties.exists() or is_yes(
        str(properties) + " already exists. Would you like to overwrite it?", options.assume_yes
    ):
        properties.write_text("JAVA_HOME=" + str(java_home) + "\nANDROID_HOME=" + str(sdk_dir) + "\nANDROID_SDK_ROOT=" + str(sdk_dir) + "\n", encoding="utf-8")
        print_success("Properties file updated successfully!")
    else:
        print_warn("Manually edit {} and set JAVA_HOME and ANDROID_SDK_ROOT.".format(properties))

    setup_ndk(options, home, prefix)
    from toolchain_native import sdk_binaries, RECORD
    sdk_binaries(sdk_dir, options.sdk)
    from android_platform import ensure_platform
    ensure_platform(sdk_dir, 35)
    record = {"runtime": "android-bionic", "architecture": "arm64-v8a", "buildTools": options.sdk,
              "jdk": options.jdk, "ndk": options.ndk if options.ndk != "0" else None,
              "sdkSource": options.manifest, "ndkSource": options.ndk_manifest}
    (sdk_dir / RECORD).write_text(json.dumps(record, ensure_ascii=False, indent=2))
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
