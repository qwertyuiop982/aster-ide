"""Fetch architecture-independent Android API resources; never fetch host build tools."""
from pathlib import Path
import hashlib
import shutil
import tempfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

REPOSITORY = 'https://dl.google.com/android/repository/'


def local(tag):
    return tag.split('}')[-1]


def child(node, name):
    return next((item for item in node if local(item.tag) == name), None)


def text(node, name):
    item = child(node, name)
    return item.text if item is not None else None


def ensure_platform(sdk, api=35):
    sdk = Path(sdk)
    destination = sdk / 'platforms' / ('android-' + str(api))
    if (destination / 'android.jar').is_file():
        return destination
    print('Installing Android API resources ' + str(api) + '…', flush=True)
    request = urllib.request.Request(REPOSITORY + 'repository2-1.xml', headers={'User-Agent': 'Aster/0.1.4'})
    with urllib.request.urlopen(request, timeout=45) as response:
        index = ET.fromstring(response.read(8 * 1024 * 1024))
    package = next(item for item in index if local(item.tag) == 'remotePackage' and item.attrib.get('path') == 'platforms;android-' + str(api))
    archive = next(item for item in child(package, 'archives') if child(item, 'host-os') is None)
    complete = child(archive, 'complete')
    filename = text(complete, 'url')
    if not filename or '/' in filename or not filename.startswith('platform-'):
        raise ValueError('Invalid Android platform resource archive')
    checksum = child(complete, 'checksum')
    kind = checksum.attrib.get('type', 'sha1') if checksum is not None else None
    with tempfile.TemporaryDirectory(prefix='.aster-platform-', dir=sdk) as temporary:
        temporary = Path(temporary)
        file = temporary / 'platform.zip'
        with urllib.request.urlopen(REPOSITORY + filename, timeout=60) as response, file.open('wb') as output:
            shutil.copyfileobj(response, output, 256 * 1024)
        if checksum is not None:
            digest = hashlib.new(kind)
            with file.open('rb') as source:
                for block in iter(lambda: source.read(256 * 1024), b''):
                    digest.update(block)
            if digest.hexdigest().lower() != checksum.text.strip().lower():
                raise ValueError('Android platform resource checksum mismatch')
        unpacked = temporary / 'unpacked'
        unpacked.mkdir()
        with zipfile.ZipFile(file) as source:
            for item in source.infolist():
                target = (unpacked / item.filename).resolve()
                if unpacked.resolve() not in target.parents or ((item.external_attr >> 16) & 0o170000) == 0o120000:
                    raise ValueError('Unsafe Android platform archive path')
            source.extractall(unpacked)
        candidates = [path.parent for path in unpacked.rglob('android.jar') if (path.parent / 'source.properties').is_file()]
        if len(candidates) != 1:
            raise ValueError('Invalid Android platform archive layout')
        destination.parent.mkdir(parents=True, exist_ok=True)
        if destination.exists():
            shutil.rmtree(destination)
        candidates[0].rename(destination)
    return destination