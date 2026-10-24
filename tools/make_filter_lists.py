#!/usr/bin/env python3
"""Builds the domain lists of the content filters (ContentFilter.kt) in app/common/src/main/assets/filters/:
 - adult.bin: StevenBlack/hosts "porn-only" (MIT, https://github.com/StevenBlack/hosts)
 - haram.bin: The Block List Project drugs, gambling and vaping lists (Unlicense, https://github.com/blocklistproject/Lists)
A file: the sorted FNV-1a 64-bit hashes of the domains (without "www."), big-endian signed longs.

    python tools/make_filter_lists.py
"""
import struct
import urllib.request

OUTPUT_DIR = 'app/common/src/main/assets/filters/'
LISTS = {
    'adult.bin': ['https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts'],
    'haram.bin': ['https://blocklistproject.github.io/Lists/alt-version/drugs-nl.txt',
                  'https://blocklistproject.github.io/Lists/alt-version/gambling-nl.txt',
                  'https://blocklistproject.github.io/Lists/alt-version/vaping-nl.txt'],
}
SKIP = {'localhost', 'localhost.localdomain', 'local', 'broadcasthost', 'ip6-localhost', 'ip6-loopback', '0.0.0.0'}


def fnv1a64(text):
    h = 0xcbf29ce484222325
    for b in text.encode('utf-8'):
        h ^= b
        h = (h * 0x100000001b3) & 0xFFFFFFFFFFFFFFFF
    return h - (1 << 64) if h >= 1 << 63 else h  # as a signed Kotlin Long


def domains_of(text):
    """Hosts files (0.0.0.0 domain) and plain domain lists"""
    for line in text.splitlines():
        parts = line.split('#')[0].split()
        if not parts:
            continue
        domain = parts[1] if len(parts) >= 2 and parts[0] in ('0.0.0.0', '127.0.0.1') else parts[0]
        domain = domain.strip().lower().rstrip('.')
        if domain in SKIP or '.' not in domain or domain[0].isdigit() and domain.replace('.', '').isdigit():
            continue
        yield domain[4:] if domain.startswith('www.') else domain


def main():
    for name, urls in LISTS.items():
        domains = set()
        for url in urls:
            domains.update(domains_of(urllib.request.urlopen(url).read().decode('utf-8', 'ignore')))
        hashes = sorted({fnv1a64(d) for d in domains})
        with open(OUTPUT_DIR + name, 'wb') as f:
            f.write(b''.join(struct.pack('>q', h) for h in hashes))
        print(f'{name}: {len(domains)} domains')


if __name__ == '__main__':
    main()
