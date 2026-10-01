#!/usr/bin/env python3
"""Inventory verified artifacts and licenses, following cached upstream parent POMs."""
from functools import lru_cache
from pathlib import Path
import json
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CACHE = Path.home() / '.gradle/caches/modules-2/files-2.1'
NS = {'v': 'https://schema.gradle.org/dependency-verification'}


@lru_cache(maxsize=2000)
def licenses_for(group, name, version, ancestors=()):
    coordinate = f'{group}:{name}:{version}'
    if coordinate in ancestors or len(ancestors) >= 12:
        return []
    for pom in sorted((CACHE / group / name / version).glob('*/*.pom')):
        try:
            root = ET.parse(pom).getroot()
        except ET.ParseError:
            continue
        licenses = [
            {'name': item.findtext('{*}name'), 'url': item.findtext('{*}url'),
             'sourcePom': coordinate}
            for item in root.findall('{*}licenses/{*}license')
        ]
        if licenses:
            return licenses
        parent = root.find('{*}parent')
        if parent is not None:
            values = tuple(parent.findtext('{*}' + field) for field in
                           ('groupId', 'artifactId', 'version'))
            if all(value and '${' not in value for value in values):
                inherited = licenses_for(*values, ancestors + (coordinate,))
                if inherited:
                    return inherited
    return []


records = []
verification = ET.parse(ROOT / 'gradle/verification-metadata.xml')
for component in verification.findall('.//v:component', NS):
    group, name, version = (component.get(key) for key in ('group', 'name', 'version'))
    licenses = licenses_for(group, name, version)
    records.append({
        'group': group, 'name': name, 'version': version,
        'licenses': licenses or [{'name': 'No cached POM license; review upstream before redistribution'}],
        'artifacts': [item.get('name') for item in component.findall('v:artifact', NS)],
    })
(ROOT / 'docs/dependency-inventory.json').write_text(json.dumps(records, indent=2) + '\n')
unresolved = sum(record['licenses'][0].get('sourcePom') is None for record in records)
print(f'Wrote {len(records)} build/runtime/test components; {unresolved} have no cached POM license')
