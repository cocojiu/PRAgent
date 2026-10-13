"""Split bounded Git raw/patch output using its NUL metadata and verified file order."""
import re


class DiffError(Exception):
    pass


def _quoted_path(value):
    raw = value.encode('utf-8')
    if not any(byte < 32 or byte >= 127 or byte in (34, 92) for byte in raw):
        return raw.decode('ascii')
    escaped = []
    controls = {7: r'\a', 8: r'\b', 9: r'\t', 10: r'\n', 11: r'\v', 12: r'\f', 13: r'\r'}
    for byte in raw:
        if byte in controls:
            escaped.append(controls[byte])
        elif byte in (34, 92):
            escaped.append('\\' + chr(byte))
        elif byte < 32 or byte >= 127:
            escaped.append('\\%03o' % byte)
        else:
            escaped.append(chr(byte))
    return '"' + ''.join(escaped) + '"'


def parse_batch(raw, entries):
    """Never infer paths from ambiguous whitespace or quoted diff headers."""
    if not entries:
        if raw:
            raise DiffError('diff_changed_during_collection')
        return []
    try:
        metadata, patches = raw.split(b'\0\0', 1)
        if len(metadata) > 512 * 1024:
            raise ValueError()
        fields, position, actual = metadata.split(b'\0'), 0, []
        while position < len(fields):
            match = re.fullmatch(rb':[0-7]{6} [0-7]{6} [a-f0-9]{4,64} [a-f0-9]{4,64} ([AMDRT][0-9]{0,3})', fields[position])
            if match is None:
                raise ValueError()
            status = match[1].decode('ascii')
            width = 3 if status.startswith('R') else 2
            if position + width > len(fields):
                raise ValueError()
            old = fields[position + 1].decode('utf-8', 'strict') if width == 3 else None
            path = fields[position + width - 1].decode('utf-8', 'strict')
            actual.append((status, path, old))
            position += width
        if actual != entries:
            raise ValueError()
        boundaries = [match.start() for match in re.finditer(rb'(?m)^diff --git ', patches)]
        if not boundaries or boundaries[0] != 0:
            raise ValueError()
        boundaries.append(len(patches))
        result, section = [], 0
        for status, path, old in entries:
            # Git represents a type change as a deletion and addition of the same path.
            count = 2 if status == 'T' else 1
            if section + count >= len(boundaries):
                raise ValueError()
            header = ('diff --git ' + _quoted_path('a/' + (old or path))
                      + ' ' + _quoted_path('b/' + path)).encode('utf-8')
            for index in range(section, section + count):
                end = patches.find(b'\n', boundaries[index])
                if end < 0 or patches[boundaries[index]:end] != header:
                    raise ValueError()
            patch = patches[boundaries[section]:boundaries[section + count]]
            if len(patch) > 256 * 1024:
                raise DiffError('file_content_budget_exceeded')
            result.append(patch.decode('utf-8', 'strict'))
            section += count
        if section != len(boundaries) - 1:
            raise ValueError()
        return result
    except (ValueError, UnicodeError, IndexError):
        raise DiffError('diff_changed_during_collection') from None
