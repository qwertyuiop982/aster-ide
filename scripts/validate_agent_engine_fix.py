"""Run agent regressions, regenerate shipped assets, then lint/build the release APK.

Requires the repository's pinned Node dependencies and configured Android toolchain.
This command does not sign, install or publish the resulting APK.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'core/app/src/main/assets/bridge'))
from agent_engines import node_runtime


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def artifact(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return {'path': str(path.relative_to(ROOT)), 'bytes': path.stat().st_size,
            'sha256': digest.hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, help='JDK directory; otherwise use the current environment')
    parser.add_argument('--android-sdk', type=Path, help='Android SDK directory; otherwise use the current environment')
    parser.add_argument('--aapt2', type=Path, help='Host-compatible AAPT2 executable for the Gradle override')
    parser.add_argument('--workers', type=int, default=4)
    args = parser.parse_args()
    if args.workers < 1:
        parser.error('--workers must be positive')

    stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    logs = ROOT / 'logs' / ('agent-engine-fix-' + stamp)
    logs.mkdir(parents=True)
    report_file = logs / 'result.json'
    report = {'status': 'running', 'startedAt': timestamp(), 'stages': [],
              'signing': 'pending', 'deviceValidation': 'pending'}

    def record():
        report_file.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

    def run(name, command):
        log = logs / (name + '.log')
        stage = {'name': name, 'status': 'running', 'command': command,
                 'startedAt': timestamp(), 'log': str(log.relative_to(ROOT))}
        report['stages'].append(stage)
        record()
        print(name + ' -> ' + str(log), flush=True)
        try:
            with log.open('w', encoding='utf-8') as output:
                result = subprocess.run(command, cwd=ROOT, env=environment,
                                        stdout=output, stderr=subprocess.STDOUT)
            stage['exitCode'] = result.returncode
            if result.returncode:
                raise RuntimeError(name + ' failed; see ' + str(log))
            stage['status'] = 'passed'
        except BaseException:
            stage['status'] = 'failed'
            raise
        finally:
            stage['finishedAt'] = timestamp()
            record()

    record()
    print('Validation report: ' + str(report_file), flush=True)
    try:
        environment = os.environ.copy()
        environment.pop('NODE_OPTIONS', None)
        environment['PYTHONDONTWRITEBYTECODE'] = '1'
        environment['CARGO_BUILD_JOBS'] = str(args.workers)
        environment['JAVA_TOOL_OPTIONS'] = (environment.get('JAVA_TOOL_OPTIONS', '') +
                                            ' -XX:ActiveProcessorCount=' + str(args.workers)).strip()
        if args.java_home:
            java_home = args.java_home.expanduser().resolve(strict=True)
            if not java_home.is_dir() or any(
                not (java_home / 'bin' / binary).is_file() or not os.access(java_home / 'bin' / binary, os.X_OK)
                for binary in ('java', 'javac')
            ):
                raise ValueError('--java-home must contain executable bin/java and bin/javac')
            environment['JAVA_HOME'] = str(java_home)
            environment['PATH'] = str(java_home / 'bin') + os.pathsep + environment.get('PATH', '')
        if args.android_sdk:
            sdk = args.android_sdk.expanduser().resolve(strict=True)
            if not sdk.is_dir():
                raise ValueError('--android-sdk must be a directory')
            environment['ANDROID_HOME'] = str(sdk)
            environment['ANDROID_SDK_ROOT'] = str(sdk)
        aapt2 = args.aapt2.expanduser().resolve(strict=True) if args.aapt2 else None
        if aapt2 is not None and (not aapt2.is_file() or not os.access(aapt2, os.X_OK)):
            raise ValueError('--aapt2 must be an executable file')
        if hasattr(os, 'sched_getaffinity'):
            cpus = sorted(os.sched_getaffinity(0))[-args.workers:]
            os.sched_setaffinity(0, cpus)
            report['cpus'] = cpus
        report['toolchain'] = {
            'wrapper': './gradlew',
            'javaHome': environment.get('JAVA_HOME'),
            'androidSdk': environment.get('ANDROID_HOME') or environment.get('ANDROID_SDK_ROOT'),
            'aapt2': str(aapt2) if aapt2 else None,
        }
        report['node'] = node_runtime(environment)
        record()
        if not report['node']['ready']:
            raise RuntimeError(report['node']['reason'] + '; resident integration tests must not be skipped')
        node = report['node']['path']
        for name in ('engine_lifecycle', 'agent_thinking', 'codex_native', 'codex_recovery',
                     'pi_transport', 'subagent_runtime'):
            run('test_' + name, [sys.executable, '-B', '-m', 'unittest', 'discover',
                                '-s', 'scripts', '-p', 'test_' + name + '.py'])
        run('test_gemini_native_paths', [node, '--test', 'scripts/test_gemini_native_paths.mjs'])
        run('test_gemini_subagents', [node, 'scripts/test_gemini_subagents.mjs'])
        run('bundle_pi', [sys.executable, '-B', 'scripts/bundle_agent.py', '--no-install'])
        run('bundle_gemini', [node, 'research/gemini-agent/build.mjs'])
        run('bundle_ui', [node, 'scripts/build_agent_ui.mjs'])
        command = ['./gradlew', ':agent:app:testReleaseUnitTest', ':app:lintRelease', ':app:assembleRelease', '--no-daemon',
                   '--max-workers=' + str(args.workers), '--no-configuration-cache', '--console=plain']
        if aapt2 is not None:
            command.append('-Pandroid.aapt2FromMavenOverride=' + str(aapt2))
        run('android_release', command)
        apk_directory = ROOT / 'core/build/application/outputs/apk/release'
        metadata = json.loads((apk_directory / 'output-metadata.json').read_text(encoding='utf-8'))
        apks = [(apk_directory / item['outputFile']).resolve(strict=True) for item in metadata['elements']]
        if not apks:
            raise RuntimeError('Gradle completed without release APK metadata entries')
        outputs = [ROOT / 'core/app/src/main/assets/bridge/agent_vendor/pi-agent.mjs',
                   ROOT / 'core/app/src/main/assets/bridge/agent_vendor/gemini-agent.mjs',
                   ROOT / 'agent/app/src/main/assets/agent/app.bundle.js', *apks]
        report['artifacts'] = [artifact(path) for path in outputs]
        report['status'] = 'checks_and_build_passed'
        print('Regressions, asset generation and Android lint/build passed. Signing and device validation remain pending.', flush=True)
        return 0
    except (Exception, KeyboardInterrupt) as error:
        report['status'] = 'interrupted' if isinstance(error, KeyboardInterrupt) else 'failed'
        report['error'] = str(error) or 'Interrupted'
        print(report['error'], file=sys.stderr, flush=True)
        return 130 if isinstance(error, KeyboardInterrupt) else 1
    finally:
        report['finishedAt'] = timestamp()
        record()
        print('Validation report: ' + str(report_file), flush=True)


if __name__ == '__main__':
    raise SystemExit(main())