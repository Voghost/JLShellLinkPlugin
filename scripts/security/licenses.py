#!/usr/bin/env python3
"""CycloneDX license coverage and distribution policy; unknown licenses block."""
import re

PERMISSIVE = {'Apache-2.0', 'MIT', 'BSD-2-Clause', 'BSD-3-Clause', 'ISC',
              '0BSD', 'CC0-1.0', 'Unlicense', 'CC-BY-4.0', 'OFL-1.1',
              'Zlib', 'BSL-1.0', 'PostgreSQL'}
ALIASES = {'The Apache Software License, Version 2.0': 'Apache-2.0',
           'Apache License, Version 2.0': 'Apache-2.0',
           'Apache License 2.0': 'Apache-2.0', 'Apache 2.0': 'Apache-2.0',
           'The MIT License': 'MIT', 'MIT License': 'MIT'}
INTERNAL = ('pkg:maven/com.jlshell.link/', 'pkg:maven/com.jlshell/',
            'pkg:maven/net.oomn.jlshell/')


def expression_allowed(expression):
    tokens = re.findall(r'\(|\)|AND|OR|[A-Za-z0-9.+-]+', expression)
    if ''.join(tokens) != re.sub(r'\s+', '', expression):
        return False
    cursor = 0

    def factor():
        nonlocal cursor
        if cursor >= len(tokens):
            raise ValueError('Incomplete license expression')
        token = tokens[cursor]
        cursor += 1
        if token == '(':
            value = disjunction()
            if cursor >= len(tokens) or tokens[cursor] != ')':
                raise ValueError('Unbalanced license expression')
            cursor += 1
            return value
        if token in ('AND', 'OR', ')'):
            raise ValueError('Invalid license expression')
        return token in PERMISSIVE

    def conjunction():
        nonlocal cursor
        value = factor()
        while cursor < len(tokens) and tokens[cursor] == 'AND':
            cursor += 1
            right = factor()
            value = value and right
        return value

    def disjunction():
        nonlocal cursor
        value = conjunction()
        while cursor < len(tokens) and tokens[cursor] == 'OR':
            cursor += 1
            right = conjunction()
            value = value or right
        return value

    try:
        return disjunction() and cursor == len(tokens)
    except ValueError:
        return False


def inventory(bom):
    entries, blocked = [], []
    for component in bom['components']:
        purl = component.get('purl', '')
        own = any(purl.startswith(prefix) for prefix in INTERNAL)
        declarations = []
        for item in component.get('licenses', []):
            license = item.get('license', {})
            value = item.get('expression') or license.get('id') or license.get('name') or ''
            value = ALIASES.get(value, value)
            # Exact upstream naming normalization, never a wildcard exception.
            if purl.startswith('pkg:maven/org.bouncycastle/') and value == 'Bouncy Castle Licence':
                value = 'MIT'
            if purl.split('?')[0] == 'pkg:maven/org.jitsi/jain-sip-ri-ossonly@1.2.279-jitsi-oss1' and value == 'Public Domain':
                value = 'LicenseRef-Jitsi-Public-Domain'
            declarations.append(value)
        acceptable = own or (bool(declarations) and all(
            expression_allowed(value) or value == 'LicenseRef-Jitsi-Public-Domain'
            for value in declarations))
        entry = {'component': purl or component.get('name', ''),
                 'licenses': declarations, 'internalComponent': own, 'passed': acceptable}
        entries.append(entry)
        if not acceptable:
            blocked.append(entry)
    return {'componentCount': len(entries), 'components': entries, 'blockingComponents': blocked}
