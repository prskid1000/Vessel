#!/usr/bin/env python3
"""Carve every embedded cabinet out of a WiX Burn bundle or IExpress SFX.

A Burn bundle is a PE with its cabinets appended: the UX container first (the
bootstrapper's own UI) and then the attached container holding the .msi files
and their payload cabs. Nothing on the Linux side reads that layout, but the
cabinets are ordinary MSCF archives sitting at known offsets, and a CAB header
carries its own total length -- so scanning for the signature and trusting the
length field recovers each one exactly.
"""
import struct, sys, os

def carve(path, outdir):
    data = open(path, 'rb').read()
    os.makedirs(outdir, exist_ok=True)
    found, pos = [], 0
    while True:
        i = data.find(b'MSCF', pos)
        if i < 0:
            break
        pos = i + 4
        # cbCabinet is at +8; reserved1 at +4 and reserved2 at +12 are zero in
        # every real header, which is what separates one from a chance match.
        if i + 36 > len(data):
            continue
        res1, size, res2 = struct.unpack_from('<III', data, i + 4)
        if res1 or res2 or size < 36 or i + size > len(data):
            continue
        out = os.path.join(outdir, 'container_%d.cab' % len(found))
        with open(out, 'wb') as fh:
            fh.write(data[i:i + size])
        found.append((i, size, out))
    for off, size, out in found:
        print('%s  offset=%d  size=%d' % (os.path.basename(out), off, size))
    return found

if __name__ == '__main__':
    if not carve(sys.argv[1], sys.argv[2]):
        sys.exit('no cabinet found in ' + sys.argv[1])
