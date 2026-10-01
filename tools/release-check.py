#!/usr/bin/env python3
"""Fail closed on unexpected release components and missing Greek resources."""
from pathlib import Path
import hashlib
import xml.etree.ElementTree as ET
ROOT = Path(__file__).resolve().parents[1]
NS = '{http://schemas.android.com/apk/res/android}'
wrapper = ROOT / 'gradle/wrapper/gradle-wrapper.jar'
assert hashlib.sha256(wrapper.read_bytes()).hexdigest() == (wrapper.parent / 'gradle-wrapper.jar.sha256').read_text().split()[0]
manifest = ROOT / 'app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml'
if not manifest.exists():
    candidates = list((ROOT / 'app/build/intermediates').glob('**/release/**/AndroidManifest.xml'))
    assert candidates, 'Build :app:assembleRelease first'
    manifest = next(p for p in candidates if 'merged_manifest' in str(p))
root = ET.parse(manifest).getroot()
app = root.find('application')
assert app.get(NS+'debuggable') != 'true'
assert app.get(NS+'allowBackup') == 'false'
assert app.get(NS+'usesCleartextTraffic') != 'true'
allowed = {'dev.magicphone.app.MainActivity': None, 'dev.magicphone.app.PhoneService': 'android.permission.BIND_ACCESSIBILITY_SERVICE', 'dev.magicphone.app.MagicTile': 'android.permission.BIND_QUICK_SETTINGS_TILE'}
for tag in ['activity', 'activity-alias', 'service', 'receiver', 'provider']:
    for component in app.findall(tag):
        name = component.get(NS+'name')
        if component.get(NS+'exported') == 'true':
            assert name in allowed, f'Unexpected exported component: {name}'
            assert component.get(NS+'permission') == allowed[name]
permissions = {p.get(NS+'name') for p in root.findall('uses-permission')}
assert not permissions & {'android.permission.QUERY_ALL_PACKAGES', 'android.permission.MANAGE_EXTERNAL_STORAGE', 'android.permission.SYSTEM_ALERT_WINDOW', 'android.permission.REQUEST_INSTALL_PACKAGES'}
english = ET.parse(ROOT/'app/src/main/res/values/strings.xml').getroot()
greek = ET.parse(ROOT/'app/src/main/res/values-el/strings.xml').getroot()
assert {x.get('name') for x in english} == {x.get('name') for x in greek}
print('PASS: wrapper SHA-256, release component allowlist, permission checks and Greek resource parity')
