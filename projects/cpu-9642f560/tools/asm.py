#!/usr/bin/env python3
"""Tiny16 two-pass assembler; no external Python dependencies."""
import argparse
import re
from pathlib import Path

ALU = dict(ADD=0, SUB=1, AND=2, OR=3, XOR=4, SHL=5, SHR=6,
           SAR=7, MOV=8, SLT=9, SLTU=10)


def assemble(source):
    labels, lines, pc = {}, [], 0
    for number, line in enumerate(source.splitlines(), 1):
        line = re.split(r"[;#]", line)[0].strip()
        if not line:
            continue
        if ":" in line:
            name, line = line.split(":", 1)
            name = name.strip()
            if not re.fullmatch(r"[A-Za-z_]\w*", name) or name in labels:
                raise ValueError(f"line {number}: invalid/duplicate label {name}")
            labels[name] = pc
            line = line.strip()
        if not line:
            continue
        parts = line.replace(",", " ").split()
        lines.append((number, pc, parts))
        pc += 2 if parts[0].upper() == "LI" else 1
    if pc > 65536:
        raise ValueError("program exceeds 65536 words")

    def reg(s):
        if not re.fullmatch(r"[rR][0-7]", s):
            raise ValueError(f"expected R0..R7, got {s}")
        return int(s[1])

    def value(s):
        return labels[s] if s in labels else int(s, 0)

    def field(n, bits, signed=False):
        lo, hi = (-(1 << (bits-1)), (1 << (bits-1))-1) if signed else (0, (1 << bits)-1)
        if not lo <= n <= hi:
            raise ValueError(f"value {n} outside {lo}..{hi}")
        return n & ((1 << bits)-1)

    words = []
    for number, pc, parts in lines:
        op, args = parts[0].upper(), parts[1:]
        try:
            expected = (1 if op in (".WORD", "JMP", "JR", "SHL", "SHR", "SAR") else
                        0 if op in ("NOP", "HALT") else 3 if op in ("LD", "ST") else 2)
            if len(args) != expected:
                raise ValueError(f"{op} expects {expected} operands")
            if op == ".WORD":
                words.append(field(value(args[0]), 16))
            elif op == "LI":
                n = value(args[1])
                if not -32768 <= n <= 65535:
                    raise ValueError("LI constant outside -32768..65535")
                n &= 65535
                d = reg(args[0]) << 9
                words.extend([0x2000 | d | (n >> 8), 0x4000 | d | (n & 255)])
            elif op in ALU:
                d = reg(args[0])
                s = 0 if op in ("SHL", "SHR", "SAR") else reg(args[1])
                words.append((d << 9) | (s << 6) | ALU[op])
            elif op in ("LDI", "LUI", "ADDI", "ORI"):
                opcode = dict(LDI=1, LUI=2, ADDI=3, ORI=4)[op]
                bits = 8 if op == "LUI" else 9
                words.append((opcode << 12) | (reg(args[0]) << 9) |
                             field(value(args[1]), bits, op in ("LDI", "ADDI")))
            elif op in ("LD", "ST"):
                words.append(((5 if op == "LD" else 6) << 12) |
                             (reg(args[0]) << 9) | (reg(args[1]) << 6) |
                             field(value(args[2]), 6, True))
            elif op in ("BZ", "BNZ", "BLTZ", "JMP"):
                target = args[-1]
                offset = value(target) - (pc + 1) if target in labels else value(target)
                # Relative branches use 16-bit modular word addresses.
                if target in labels:
                    offset = ((offset + 32768) & 65535) - 32768
                opcode = dict(BZ=7, BNZ=8, BLTZ=9, JMP=10)[op]
                words.append((opcode << 12) | (0 if op == "JMP" else reg(args[0]) << 9) |
                             field(offset, 12 if op == "JMP" else 9, True))
            elif op == "JR":
                words.append(0xb000 | (reg(args[0]) << 9))
            elif op in ("NOP", "HALT"):
                words.append(0xe000 if op == "NOP" else 0xf000)
            else:
                raise ValueError(f"unknown instruction {op}")
        except (ValueError, IndexError) as exc:
            raise ValueError(f"line {number}: {exc}") from exc
    return words


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("-o", "--output", required=True, type=Path)
    parser.add_argument("--depth", type=int, help="pad ROM to this many words with HALT")
    args = parser.parse_args()
    words = assemble(args.source.read_text())
    if args.depth is not None:
        if not len(words) <= args.depth <= 65536:
            parser.error("depth must cover the program and be <= 65536")
        words += [0xf000] * (args.depth - len(words))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("".join(f"{w:04x}\n" for w in words))
    print(f"{args.output}: {len(words)} words")


if __name__ == "__main__":
    main()
