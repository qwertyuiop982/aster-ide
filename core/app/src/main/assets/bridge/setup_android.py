#!/usr/bin/env python3
"""Aster entry point for the embedded Android/Bionic idesetup installer."""
from pathlib import Path
import argparse
import json
import os
import shlex
import shutil
import sys

import idesetup
from toolchain_native import installed


def arguments(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--sdk', default=os.environ.get('ANDROID_HOME', str(Path.home() / 'android-sdk')))
    parser.add_argument('--build-tools', choices=['35.0.1', '34.0.4'], default='35.0.1')
    parser.add_argument('--jdk', choices=['17', '21'], default='17')
    parser.add_argument('--ndk', choices=['29.0.14206865', '29.0.14033849', '28.2.13676358', '27.3.13750724', '0'], default='29.0.14206865')
    parser.add_argument('--skip-cmdline-tools', action='store_true')
    return parser.parse_args(argv)


def installer_arguments(options):
    sdk = Path(options.sdk).expanduser().absolute()
    if any(character in str(sdk) for character in ('\n', '\r', '\0')):
        raise ValueError('Invalid SDK destination')
    prefix = os.environ.get('PREFIX')
    apt = shutil.which('apt-get')
    if not prefix or not apt:
        raise RuntimeError('Android SDK/NDK installation must run in the embedded Termux environment')
    result = ['-i', str(sdk.parent), '--sdk-root', str(sdk), '-s', options.build_tools, '-j', options.jdk,
              '-n', options.ndk, '-a', 'aarch64', '-y', '--managed-runtime',
              '-p', shlex.join([apt, '-o', 'Acquire::Retries=1', '-o', 'Acquire::https::Timeout=30', '-o', 'DPkg::Lock::Timeout=30'])]
    if options.skip_cmdline_tools:
        result.append('-c')
    return result


def main(argv=None):
    options = arguments(argv)
    print('Installing Android/Bionic SDK and NDK with embedded idesetup.py…', flush=True)
    code = idesetup.main(installer_arguments(options))
    if code:
        return code
    info = installed(options.sdk)
    if not info:
        raise RuntimeError('Android/Bionic toolchain validation failed')
    print('Android/Bionic toolchain ready: ' + json.dumps(info, ensure_ascii=False), flush=True)
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (idesetup.SetupError, OSError, ValueError, RuntimeError) as error:
        print('[ ERROR ] ' + str(error), file=sys.stderr)
        raise SystemExit(1)