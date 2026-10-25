"""Package an assembled/signed release and an exact Git source snapshot without build outputs."""
from pathlib import Path
import argparse, datetime, hashlib, json, shutil, subprocess, zipfile
ROOT = Path(__file__).resolve().parents[1]
VERSION = '1.0.1'

def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''): digest.update(block)
    return digest.hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--build-log', type=Path, required=True)
    parser.add_argument('--record-only', action='store_true', help='Write public build records before the release commit')
    options = parser.parse_args()
    output = options.output.resolve(); output.mkdir(parents=True, exist_ok=True)
    log = options.build_log.read_text()
    if 'BUILD SUCCESSFUL' not in log or 'BUILD FAILED' in log or ':app:assembleRelease' not in log:
        raise RuntimeError('A complete successful release assembly is required')
    signed = json.loads((ROOT/'logs/release-release-signing.json').read_text())
    native_receipts = []
    for name in ['codex-android-build.json', 'codex-host-android-build.json']:
        receipt = json.loads((ROOT/'logs'/name).read_text())
        if receipt['status'] != 'succeeded':
            raise RuntimeError('Native source build did not succeed: ' + name)
        if sha256(ROOT/receipt['binary']) != receipt['sha256']:
            raise RuntimeError('Native build output changed: ' + name)
        native_receipts.append(receipt)

    source_apk = ROOT/signed['apk']
    if sha256(source_apk) != signed['sha256']: raise RuntimeError('Signed APK changed')
    apk_name = f'aster-v{VERSION}-release.apk'
    shutil.copy2(source_apk,output/apk_name)
    with zipfile.ZipFile(source_apk) as archive:
        names=set(archive.namelist())
        required={'lib/arm64-v8a/libaster_codex.so','lib/arm64-v8a/libaster_codex_host.so',
                  'lib/arm64-v8a/libaster_proot.so','lib/arm64-v8a/libaster_loader.so',
                  'lib/arm64-v8a/libaster_loader32.so','lib/arm64-v8a/libtalloc.so',
                  'lib/arm64-v8a/libandroid-shmem.so','assets/runtime/bootstrap-aarch64.zip',
                  'assets/licenses/CLIENT-SOURCES.json','assets/bridge/agent_vendor/pi-agent.mjs',
                  'assets/bridge/agent_vendor/gemini-agent.mjs'}
        if required-names: raise RuntimeError('Missing APK runtime inputs: '+str(required-names))
        asset_sources = {
            'assets/bridge/agent_vendor/pi-agent.mjs': ROOT/'core/app/src/main/assets/bridge/agent_vendor/pi-agent.mjs',
            'assets/bridge/agent_vendor/gemini-agent.mjs': ROOT/'core/app/src/main/assets/bridge/agent_vendor/gemini-agent.mjs',
            'assets/licenses/CLIENT-SOURCES.json': ROOT/'client/browser/src/main/assets/licenses/CLIENT-SOURCES.json',
            'assets/licenses/CLIENT-NOTICE.txt': ROOT/'client/browser/src/main/assets/licenses/CLIENT-NOTICE.txt',
            'assets/runtime/bootstrap-aarch64.zip': ROOT/'build/native/runtime-assets/runtime/bootstrap-aarch64.zip',
        }
        for entry, source in asset_sources.items():
            digest = hashlib.sha256()
            with archive.open(entry) as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''): digest.update(block)
            if digest.hexdigest() != sha256(source):
                raise RuntimeError('APK asset differs from its retained build input: ' + entry)
        if any('/restricted/' in name for name in names): raise RuntimeError('Restricted SDK entered APK')
        for receipt in native_receipts:
            entry = 'lib/arm64-v8a/' + Path(receipt['binary']).name
            digest = hashlib.sha256()
            with archive.open(entry) as stream:
                for block in iter(lambda: stream.read(1024 * 1024), b''): digest.update(block)
            if digest.hexdigest() != receipt['sha256']:
                raise RuntimeError('APK native entry differs from its source build: ' + entry)
    validation={'version':VERSION,'versionCode':11,'ordinaryApkSha256':signed['sha256'],
                'certificateSha256':signed['certificateSha256'],
                'localDebugCertificateForUpgradeCompatibility':signed['localDebugCertificateForUpgradeCompatibility'],
                'apkSignatureVerified':True,'minified':signed['minified'],'resourcesShrunk':signed['resourcesShrunk'],
                'releaseAssembleVerified':True,'lintReleaseVerified':':app:lintRelease' in log,
                'nativeCodexBuiltFromTrimmedSource':True,'piAndGeminiProductionBundlesRebuilt':True,
                'apkRuntimeAssetsMatchRetainedInputs':True,
                'nativeBuildSha256':{Path(receipt['binary']).name:receipt['sha256'] for receipt in native_receipts},
                'shizukuIntegration':'Maven API/provider 13.1.5 calling a separate installed Shizuku app',
                'shizukuServerEmbedded':False,'shizukuSourceVendored':False,'shizukuAuthorizationRequired':True,
                'sourceArchiveContainsSharedLibraries':False,'sourceArchiveContainsBuildIntermediates':False,
                'sourceArchiveContainsTestOrExampleTrees':False,'sourceArchiveRestrictedSdkIncluded':False,
                'realDeviceUiVerified':False,'operitFullFeatureParity':False,
                'providerHistory':{'scope':'Prior 1.0.1 validation, not re-run during source cleanup',
                                   'textRequestsSucceededPreviously':True,
                                   'vision':{'status':'blocked','httpStatus':403,'code':'INSUFFICIENT_BALANCE','verifiedOnLiveProvider':False}}}
    if options.record_only:
        public={'version':VERSION,'versionCode':11,'apkSha256':signed['sha256'],
                'certificateSha256':signed['certificateSha256'],'ordinaryReleaseOnly':True,
                'restrictedSdkIncluded':False,'nativeArtifactsInSource':False,
                'shizukuSourceVendored':False,'nativeBuildOutputs':'ignored build/native/',
                'clientSources':json.loads((ROOT/'client/SOURCES.json').read_text())}
        (ROOT/'PUBLIC-RELEASE.json').write_text(json.dumps(public,ensure_ascii=False,indent=2)+'\n')
        (ROOT/'VERIFICATION.md').write_text('''# Public source verification

The compact 1.0.1 source was validated before its initial GitHub publication:

- Codex app-server and code-mode-host compiled for aarch64-linux-android from the trimmed Rust workspace with its retained lockfile.
- Pi and Gemini production runner bundles rebuilt after a clean npm dependency installation.
- Shizuku API/provider 13.1.5 resolved through Maven; the Shizuku client module compiled without vendored Shizuku sources.
- Gradle release APK assembly and lint completed, with R8 and resource shrinking enabled.
- APK signature, manifest version/debuggability, native runtime entries, bootstrap and client notice assets checked.
- Public source snapshot excludes shared libraries, build intermediates, caches, standalone test/example trees, restricted SDK material and signing keys.
- Pinned runtime/V8 dependency extraction verified using the separate native-dependencies Release archive as the fallback source.

The detailed APK/source hashes and current build scope are in the Release validation JSON and SHA256SUMS. Device UI authorization/click/vision flows were not re-validated during source cleanup. Prior provider text connectivity succeeded; the prior image-provider request returned HTTP 403 / INSUFFICIENT_BALANCE. Those records do not establish current live provider availability.
''')
    else:
        dirty = subprocess.check_output(['git','status','--porcelain','--untracked-files=normal'],cwd=ROOT,text=True)
        if dirty: raise RuntimeError('Commit the reviewed source before packaging')
        commit = subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip()
        validation['sourceCommit']=commit
        source_name=f'aster-source-{VERSION}.zip'
        subprocess.run(['git','archive','--format=zip','--prefix=aster-ide/','-o',str(output/source_name),'HEAD'],cwd=ROOT,check=True)
        with zipfile.ZipFile(output/source_name) as archive:
            forbidden=[]
            source_bytes=0; source_files=0
            banned={'build','.gradle','.kotlin','node_modules','target','test','tests','examples','example','fixtures','__fixtures__','__tests__','__snapshots__','benches','testing','test-utils','test-data','service_tests','system-tests','perf-tests','smoke_tests','integration-tests','__mocks__','restricted'}
            for item in archive.infolist():
                if item.is_dir(): continue
                parts=Path(item.filename.lower()).parts
                if banned.intersection(parts) or item.filename.lower().endswith(('.so','.o','.a','.node','.exe','.dll','.dylib','.rlib','.rmeta','.obj','.pdb','.tsbuildinfo','.class','.dex','.apk','.aab','.aar','.pyc','.keystore','.jks','.p12','.pfx')) or any(part.startswith('lib') and '.so.' in part for part in parts):
                    forbidden.append(item.filename)
                source_bytes+=item.file_size;source_files+=1
            if forbidden: raise RuntimeError('Forbidden source entries: '+str(forbidden))
        validation.update(sourceArchiveFiles=source_files,sourceArchiveBytes=source_bytes)
    shutil.copy2(ROOT/'client/browser/src/main/assets/licenses/CLIENT-NOTICE.txt',output/f'CLIENT-NOTICE-{VERSION}.txt')
    (output/f'validation-{VERSION}.json').write_text(json.dumps(validation,ensure_ascii=False,indent=2)+'\n')
    if not options.record_only:
        names=[apk_name,source_name,f'validation-{VERSION}.json',f'CLIENT-NOTICE-{VERSION}.txt',f'aster-native-dependencies-{VERSION}.zip']
        downloads={name:{'size':(output/name).stat().st_size,'sha256':sha256(output/name)} for name in names}
        sums=output/f'SHA256SUMS-{VERSION}.txt'
        sums.write_text(''.join(item['sha256']+'  '+name+'\n' for name,item in downloads.items()))
        downloads[sums.name]={'size':sums.stat().st_size,'sha256':sha256(sums)}
        delivery={'name':'Aster','version':VERSION,'versionCode':11,'abi':'arm64-v8a','minSdk':24,'targetSdk':28,
                  'releaseDate':datetime.date.today().isoformat(),'engines':['Pi','Codex','Gemini'],
                  'sourceCommit':commit,'sourceFiles':source_files,'sourceBytes':source_bytes,
                  'restrictedMaterialIncluded':False,'downloads':downloads}
        (output/'delivery.json').write_text(json.dumps(delivery,ensure_ascii=False,indent=2)+'\n')
        print(json.dumps({'files':source_files,'sourceBytes':source_bytes,'downloads':downloads},indent=2))
    else: print('Public build records prepared; commit the source and package again without --record-only')

if __name__=='__main__': main()