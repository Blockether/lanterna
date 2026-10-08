#!/usr/bin/env python3
"""Generate UnicodeWidthData.java from the Unicode Character Database.

Use the same UCD files as the uucode copy of OpenTUI (Unicode 17.0.0):

    python3 scripts/gen_unicode_width.py <ucd-dir> > \
        src/main/java/com/googlecode/lanterna/UnicodeWidthData.java
"""
import sys
from pathlib import Path

LICENSE = '''/*
 * This file is part of lanterna (https://github.com/mabe02/lanterna).
 *
 * lanterna is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2010-2024 Martin Berglund
 */
'''

# Grapheme break classes of uucode (src/types.zig, GraphemeBreak).
GB = ["other", "control", "prepend", "cr", "lf", "regional_indicator", "spacing_mark",
      "l", "v", "t", "lv", "lvt", "zwj", "zwnj", "extended_pictographic",
      "emoji_modifier_base", "emoji_modifier", "indic_conjunct_break_extend",
      "indic_conjunct_break_linker", "indic_conjunct_break_consonant"]
ORIGINAL_GB = {"Other": "other", "Control": "control", "Prepend": "prepend", "CR": "cr", "LF": "lf",
               "Regional_Indicator": "regional_indicator", "SpacingMark": "spacing_mark", "L": "l",
               "V": "v", "T": "t", "LV": "lv", "LVT": "lvt", "ZWJ": "zwj", "Extend": "extend"}
# General category classes that the width rules need.
GC_OTHER, GC_MN, GC_MC, GC_ME, GC_ZERO = range(5)
N = 0x110000
# Extra wide ranges of OpenTUI eawToWidth (packages/native/src/utf8.zig).
EXTRA_WIDE = [
    (0x203C, 0x203C), (0x2049, 0x2049), (0x231A, 0x231A), (0x231B, 0x231B),
    (0x2329, 0x2329), (0x232A, 0x232A), (0x23E9, 0x23EC), (0x23F0, 0x23F0),
    (0x23F3, 0x23F3), (0x25FD, 0x25FE), (0x2614, 0x2615), (0x2622, 0x2622),
    (0x2623, 0x2623), (0x2630, 0x2637), (0x2648, 0x2653), (0x267F, 0x267F),
    (0x2693, 0x2693), (0x269B, 0x269B), (0x26AA, 0x26AB), (0x26BD, 0x26BE),
    (0x26C4, 0x26C5), (0x26CE, 0x26CE), (0x26D1, 0x26D1), (0x26D4, 0x26D4),
    (0x26EA, 0x26EA), (0x26F2, 0x26F2), (0x26F3, 0x26F3), (0x26F5, 0x26F5),
    (0x26FA, 0x26FA), (0x26FD, 0x26FD), (0x2705, 0x2705), (0x270A, 0x270B),
    (0x2728, 0x2728), (0x274C, 0x274C), (0x274E, 0x274E), (0x2753, 0x2755),
    (0x2757, 0x2757), (0x2760, 0x2767), (0x2795, 0x2797), (0x27B0, 0x27B0),
    (0x27BF, 0x27BF), (0x2B1B, 0x2B1C), (0x2B50, 0x2B50), (0x2B55, 0x2B55),
    (0x1F000, 0x1F02B), (0x1F030, 0x1F093), (0x1F0A0, 0x1F0AE), (0x1F0B1, 0x1F0BF),
    (0x1F0C1, 0x1F0CF), (0x1F0D1, 0x1F0F5), (0x1F300, 0x1F320), (0x1F32D, 0x1F335),
    (0x1F337, 0x1F37C), (0x1F37E, 0x1F393), (0x1F3A0, 0x1F3CA), (0x1F3CF, 0x1F3D3),
    (0x1F3E0, 0x1F3F0), (0x1F3F4, 0x1F3F4), (0x1F3F8, 0x1F3FF), (0x1F400, 0x1F43E),
    (0x1F440, 0x1F440), (0x1F442, 0x1F4FC), (0x1F4FF, 0x1F6C5), (0x1F6CC, 0x1F6CC),
    (0x1F6D0, 0x1F6D2), (0x1F6D5, 0x1F6D7), (0x1F6DC, 0x1F6DF), (0x1F6EB, 0x1F6EC),
    (0x1F6F4, 0x1F6FC), (0x1F700, 0x1F773), (0x1F780, 0x1F7D8), (0x1F7E0, 0x1F7EB),
    (0x1F800, 0x1F80B), (0x1F810, 0x1F847), (0x1F850, 0x1F859), (0x1F860, 0x1F887),
    (0x1F890, 0x1F8AD), (0x1F8B0, 0x1F8B1), (0x1F90C, 0x1F93A), (0x1F93C, 0x1F945),
    (0x1F947, 0x1FA53), (0x1FA60, 0x1FA6D), (0x1FA70, 0x1FA74), (0x1FA78, 0x1FA7C),
    (0x1FA80, 0x1FA86), (0x1FA90, 0x1FAAC), (0x1FAB0, 0x1FABA), (0x1FAC0, 0x1FAC5),
    (0x1FAD0, 0x1FAD9), (0x1FAE0, 0x1FAE7), (0x1FAF0, 0x1FAF8),
]


def rows(path):
    for line in path.read_text(encoding="utf-8").splitlines():
        data = line.split("#", 1)[0].strip()
        if not data:
            continue
        fields = [f.strip() for f in data.split(";")]
        lo, _, hi = fields[0].partition("..")
        yield int(lo, 16), int(hi or lo, 16), fields[1:]


def main(ucd):
    ucd = Path(ucd)
    wide = bytearray(N)
    for line in (ucd / "extracted/DerivedEastAsianWidth.txt").read_text().splitlines():
        if line.startswith("# @missing:") and line.rstrip().endswith("Wide"):
            lo, hi = line[len("# @missing:"):].split(";")[0].strip().split("..")
            for cp in range(int(lo, 16), int(hi, 16) + 1):
                wide[cp] = 1
    for lo, hi, f in rows(ucd / "extracted/DerivedEastAsianWidth.txt"):
        for cp in range(lo, hi + 1):
            wide[cp] = 1 if f[0] in ("W", "F") else 0
    for lo, hi in EXTRA_WIDE:
        for cp in range(lo, hi + 1):
            wide[cp] = 1
    gc = bytearray(N)
    ignorable = bytearray(N)
    for lo, hi, f in rows(ucd / "extracted/DerivedGeneralCategory.txt"):
        value = {"Mn": GC_MN, "Mc": GC_MC, "Me": GC_ME, "Cc": GC_ZERO, "Cs": GC_ZERO,
                 "Zl": GC_ZERO, "Zp": GC_ZERO}.get(f[0], GC_OTHER)
        for cp in range(lo, hi + 1):
            gc[cp] = value
    incb = {}
    for lo, hi, f in rows(ucd / "DerivedCoreProperties.txt"):
        for cp in range(lo, hi + 1):
            if f[0] == "Default_Ignorable_Code_Point":
                ignorable[cp] = 1
            elif f[0] == "InCB":
                incb[cp] = f[1]
    original = ["other"] * N
    for lo, hi, f in rows(ucd / "auxiliary/GraphemeBreakProperty.txt"):
        for cp in range(lo, hi + 1):
            original[cp] = ORIGINAL_GB[f[0]]
    emoji = {}
    for lo, hi, f in rows(ucd / "emoji/emoji-data.txt"):
        for cp in range(lo, hi + 1):
            emoji.setdefault(cp, set()).add(f[0])
    packed = []
    for cp in range(N):
        props = emoji.get(cp, ())
        if "Emoji_Modifier" in props:
            gb = "emoji_modifier"
        elif "Emoji_Modifier_Base" in props:
            gb = "emoji_modifier_base"
        elif "Extended_Pictographic" in props:
            gb = "extended_pictographic"
        elif cp in incb and original[cp] in ("extend", "zwj", "other"):
            gb = {"Extend": "zwj" if cp == 0x200D else "indic_conjunct_break_extend",
                  "Linker": "indic_conjunct_break_linker",
                  "Consonant": "indic_conjunct_break_consonant"}[incb[cp]]
        elif original[cp] == "extend":
            if cp != 0x200C:
                raise SystemExit("unexpected Extend without InCB: %04X" % cp)
            gb = "zwnj"
        else:
            gb = original[cp]
        if gc[cp] == GC_ZERO:
            standalone = 0
        elif cp == 0x00AD:
            standalone = 1
        elif ignorable[cp]:
            standalone = 0
        else:
            standalone = 1
        zero = (standalone == 0 or gb == "emoji_modifier" or gc[cp] in (GC_MN, GC_ME)
                or original[cp] in ("v", "t", "prepend"))
        packed.append(GB.index(gb) | gc[cp] << 5 | wide[cp] << 8 | int(zero) << 9)
    runs = [(cp, packed[cp]) for cp in range(N) if cp == 0 or packed[cp] != packed[cp - 1]]
    chunks, chunk = [], []
    for run in runs:
        chunk.append("%x,%x" % run)
        if len(chunk) == 4000:
            chunks.append(";".join(chunk))
            chunk = []
    if chunk:
        chunks.append(";".join(chunk))
    out = sys.stdout
    out.write(LICENSE)
    out.write("package com.googlecode.lanterna;\n\n")
    out.write("/**\n * Unicode 17.0.0 properties for {@link UnicodeWidth}.\n")
    out.write(" * Generated by {@code scripts/gen_unicode_width.py}. Do not edit by hand.\n */\n")
    out.write("final class UnicodeWidthData {\n")
    out.write("    private UnicodeWidthData() {\n    }\n\n")
    out.write("    /** Runs of {@code start,value} in hex, separated by {@code ;}. */\n")
    out.write("    static final String[] RUNS = {\n")
    for i, chunk in enumerate(chunks):
        out.write('        "%s"%s\n' % (chunk, "," if i + 1 < len(chunks) else ""))
    out.write("    };\n}\n")
    print("runs=%d chunks=%d" % (len(runs), len(chunks)), file=sys.stderr)


if __name__ == "__main__":
    main(sys.argv[1])
