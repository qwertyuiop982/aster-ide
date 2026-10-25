"""Validate Android ARM64 tool executables and installed native SDK/NDK layout."""
from pathlib import Path
import json
import struct

RECORD = '.aster-toolchain.json'


def android_elf(path):
    path = Path(path)
    with path.open('rb') as source:
        header = source.read(64)
        if len(header) < 64 or header[:4] != b'\x7fELF' or header[4] != 2 or header[5] != 1:
            raise ValueError(f'{path.name} 不是 ARM64 ELF 工具')
        if struct.unpack_from('<H', header, 18)[0] != 183:
            raise ValueError(f'{path.name} 的 CPU 架构不适用于 arm64-v8a')
        offset = struct.unpack_from('<Q', header, 32)[0]
        entry_size, count = struct.unpack_from('<HH', header, 54)
        if entry_size < 56 or count == 0 or count > 256 or offset < 64 or offset > 1024 * 1024:
            raise ValueError('ELF 程序头无效')
        interpreter = None
        dynamic = False
        for index in range(count):
            source.seek(offset + index * entry_size)
            program = source.read(56)
            if len(program) != 56:
                raise ValueError('ELF 程序头不完整')
            kind, flags, position, virtual, physical, size, memory, align = struct.unpack('<IIQQQQQQ', program)
            dynamic |= kind == 2
            if kind == 3:
                if interpreter is not None or size < 2 or size > 512 or position > 16 * 1024 * 1024:
                    raise ValueError('ELF 解释器字段无效')
                source.seek(position)
                value = source.read(size)
                if len(value) != size or not value.endswith(b'\0'):
                    raise ValueError('ELF 解释器字段不完整')
                interpreter = value[:-1].decode('ascii')
        if interpreter is not None and interpreter != '/system/bin/linker64':
            raise ValueError(f'{path.name} 使用 {interpreter}，需要 Android/Bionic 工具链')
        if dynamic and interpreter is None:
            raise ValueError(f'{path.name} 缺少 Android ELF 解释器')
    return {'architecture': 'arm64-v8a', 'interpreter': interpreter or 'static', 'runtime': 'android-bionic' if interpreter else 'android-native-static'}


def sdk_binaries(sdk, version):
    sdk = Path(sdk)
    tools = sdk / 'build-tools' / version
    result = {name: android_elf(tools / name) for name in ('aapt2', 'zipalign')}
    for name in ('aapt', 'aidl', 'dexdump', 'split-select'):
        if (tools / name).is_file():
            result[name] = android_elf(tools / name)
    if (sdk / 'platform-tools/adb').is_file():
        result['adb'] = android_elf(sdk / 'platform-tools/adb')
    return result


def ndk_binaries(ndk):
    ndk = Path(ndk)
    candidates = sorted((ndk / 'toolchains/llvm/prebuilt').glob('*/bin/clang'))
    if not candidates:
        raise ValueError('NDK 中没有 clang')
    result = {}
    for clang in candidates:
        host = {}
        for name in ('clang', 'clang++', 'ld.lld', 'llvm-ar'):
            host[name] = android_elf(clang.parent / name)
        if not result:
            result = host
    return result


def installed(sdk):
    sdk = Path(sdk)
    try:
        info = json.loads((sdk / RECORD).read_text())
        if info.get('runtime') != 'android-bionic':
            return None
        sdk_binaries(sdk, info['buildTools'])
        if info.get('ndk'):
            ndk_binaries(sdk / 'ndk' / info['ndk'])
        return info
    except (OSError, ValueError, KeyError, struct.error, UnicodeError):
        return None


def native_aapt2(sdk, version):
    path = Path(sdk) / 'build-tools' / version / 'aapt2'
    try:
        android_elf(path)
        return str(path)
    except (OSError, ValueError, struct.error, UnicodeError):
        return None