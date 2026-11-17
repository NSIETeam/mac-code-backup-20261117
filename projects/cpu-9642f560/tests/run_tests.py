#!/usr/bin/env python3
"""Differential simulation against an instruction-level Python reference."""
import os
import random
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from asm import assemble


def tool(name, env):
    local = ROOT / ".tools" / "iverilog" / "bin" / name
    return os.environ.get(env) or shutil.which(name) or str(local)


def sext(n, bits):
    n &= (1 << bits)-1
    return n - (1 << bits) if n & (1 << (bits-1)) else n


def reference(program):
    regs, memory, events = [None]*8, {}, []
    pc, cycles, count = 0, 1, 0

    def read(r):
        if regs[r] is None:
            raise AssertionError(f"uninitialized R{r} at {pc:04x}")
        return regs[r]

    while count < 10000:
        ins = program.get(pc, 0xf000)
        op, d, s, fn = ins >> 12, (ins >> 9) & 7, (ins >> 6) & 7, ins & 15
        nxt, taken, fault, halt = (pc+1) & 65535, False, False, False
        value = None
        if op == 0:
            fault = bool(ins & 0x30) or fn > 10
            if not fault:
                if fn == 8:
                    value = read(s)
                else:
                    a = read(d)
                    if fn in (5, 6, 7):
                        value = a << 1 if fn == 5 else a >> 1 if fn == 6 else sext(a, 16) >> 1
                    else:
                        b = read(s)
                        value = {0: lambda: a+b, 1: lambda: a-b,
                                 2: lambda: a & b, 3: lambda: a | b,
                                 4: lambda: a ^ b,
                                 9: lambda: int(sext(a, 16) < sext(b, 16)),
                                 10: lambda: int(a < b)}[fn]()
        elif op == 1:
            value = sext(ins, 9)
        elif op == 2:
            fault = bool(ins & 0x100)
            value = (ins & 255) << 8
        elif op == 3:
            value = read(d) + sext(ins, 9)
        elif op == 4:
            value = read(d) | (ins & 511)
        elif op in (5, 6):
            address = (read(s) + sext(ins, 6)) & 65535
            if op == 5:
                value = memory.get(address, (address*73+19) & 65535)
                cycles += 1
            else:
                memory[address] = read(d)
                events.append(("W", address, read(d)))
        elif op in (7, 8, 9):
            a = read(d)
            taken = a == 0 if op == 7 else a != 0 if op == 8 else a >= 32768
            if taken:
                nxt = (nxt + sext(ins, 9)) & 65535
        elif op == 10:
            taken = True
            nxt = (nxt + sext(ins, 12)) & 65535
        elif op == 11:
            fault = bool(ins & 511)
            if not fault:
                taken, nxt = True, read(d)
        elif op in (14, 15):
            fault = bool(ins & 4095)
            halt = op == 15
        else:
            fault = True
        if value is not None and not fault:
            regs[d] = value & 65535
        cycles += 1 + int(taken and not fault)
        count += 1
        events.append(("R", pc, ins, tuple(regs)))
        if fault or halt:
            return events, (cycles, count, int(fault))
        pc = nxt
    raise AssertionError("reference did not halt")


def simulate(name, program, vvp, executable):
    path = ROOT / "build" / "tests" / name
    path.with_suffix(".hex").write_text("".join(f"@{a:04x}\n{w:04x}\n" for a, w in sorted(program.items())))
    output = subprocess.run([vvp, str(executable), f"+ROM={path}.hex", f"+TRACE={path}.trace"],
                            cwd=ROOT, text=True, capture_output=True, timeout=20)
    if output.returncode:
        raise AssertionError(f"{name}: simulator failed\n{output.stdout}\n{output.stderr}")
    expected, end = reference(program)
    lines = path.with_suffix(".trace").read_text().splitlines()
    assert len(lines) == len(expected)+1, (name, "event count", len(lines), len(expected))
    for index, (line, event) in enumerate(zip(lines, expected)):
        fields = line.split()
        assert fields[0] == event[0], (name, index, line, event)
        if event[0] == "W":
            assert tuple(int(x, 16) for x in fields[1:]) == event[1:], (name, index, line, event)
        else:
            assert (int(fields[1], 16), int(fields[2], 16)) == event[1:3], (name, index, line, event)
            for r, wanted in enumerate(event[3]):
                if wanted is not None:
                    assert fields[r+3] == f"{wanted:04x}", (name, index, f"R{r}", line, event)
    assert lines[-1] == "END " + " ".join(map(str, end)), (name, lines[-1], end)
    print(f"PASS {name}: {end[1]} instructions, {end[0]} cycles")
    return end[1]


def programs():
    init = "\n".join(f"LDI R{i}, {i}" for i in range(8)) + "\n"
    yield "sum", assemble((ROOT / "programs/sum.asm").read_text())
    yield "dependencies", assemble(init + """
        LI R0, 0xffff
        LDI R1, 1
        ADD R0, R1
        SUB R0, R1
        MOV R2, R0
        XOR R2, R0
        OR R2, R1
        AND R2, R0
        SHL R2
        SHR R2
        LI R3, 0x8001
        SAR R3
        ORI R3, 511
        ADDI R3, -256
        ADDI R3, 255
        HALT
    """)
    yield "memory_hazards", assemble(init + """
        LDI R0, 0
        LI R1, 0xbeef
        ST R1, R0, -1
        LD R2, R0, -1
        ADD R2, R1
        ST R2, R0, 31
        LD R3, R0, 31
        ST R3, R0, -32
        LD R4, R0, -32
        LD R0, R0, -1
        ST R0, R0, 1
        LD R0, R0, 1
        LD R5, R0, 0
        LD R6, R0, 1
        HALT
    """)
    yield "control", assemble(init + """
        BZ R0, first
        ST R7, R0, 0
        HALT
    first:
        BNZ R0, bad
        BLTZ R1, bad
        LDI R1, -1
        BLTZ R1, second
        ST R7, R0, 1
        HALT
    second:
        BZ R1, bad
        BNZ R1, third
        ST R7, R0, 2
    third:
        JMP fourth
        ST R7, R0, 3
    fourth:
        LI R6, subroutine
        LI R7, returned
        JR R6
        ST R7, R0, 4
    returned:
        ST R0, R0, 5
        LD R1, R0, 5
        BZ R1, done
    bad:
        .word 0xc000
    subroutine:
        ADDI R5, 1
        JR R7
        ST R7, R0, 6
    done:
        HALT
    """)
    # Signed overflow and unsigned borrow boundaries, with identical operands too.
    text = init
    for a in [0, 1, 0x7fff, 0x8000, 0xfffe, 0xffff]:
        for b in [0, 1, 0x7fff, 0x8000, 0xfffe, 0xffff]:
            for op in ["ADD", "SUB", "SLT", "SLTU"]:
                text += f"LI R0, {a}\nLI R1, {b}\n{op} R0, R1\n"
    text += "SLT R0, R0\nSLTU R1, R1\nHALT\n"
    yield "arithmetic_edges", assemble(text)
    yield "pc_wrap", {0: 0x1000, 1: 0x13ff, 2: 0xb200, 65535: 0xf000}
    yield "branch_wrap", {0: 0xaffd, 65534: 0xe000, 65535: 0xf000}
    wrap = dict(enumerate(assemble("""
        LDI R0, 0
        LD R1, R0, 0
        ADDI R1, -19
        BNZ R1, done
        LDI R1, 20
        ST R1, R0, 0
        LI R2, 0xffff
        JR R2
    done:
        HALT
    """)))
    wrap[65535] = 0xe000
    yield "sequential_wrap", wrap
    for bad in [0xc000, 0xd000, 0x0010, 0x000b, 0x2100, 0xb001, 0xe001, 0xf001]:
        yield f"illegal_{bad:04x}", assemble(init) + [bad, 0x6000, 0xf000]
    for seed in range(16):
        rng = random.Random(seed)
        text = init
        for _ in range(350):
            d, s = rng.randrange(8), rng.randrange(8)
            kind = rng.randrange(9)
            if kind == 0:
                text += f"LI R{d}, {rng.randrange(65536)}\n"
            elif kind == 1:
                text += f"{rng.choice(['SHL','SHR','SAR'])} R{d}\n"
            elif kind == 2:
                text += f"{rng.choice(['LD','ST'])} R{d}, R{s}, {rng.randrange(-32,32)}\n"
            elif kind == 3:
                text += f"{rng.choice(['BZ','BNZ','BLTZ'])} R{d}, 1\nST R{s}, R{d}, 0\n"
            elif kind == 4:
                text += f"ADDI R{d}, {rng.randrange(-256,256)}\n"
            elif kind == 5:
                text += f"ORI R{d}, {rng.randrange(512)}\n"
            elif kind == 6:
                text += "JMP 1\n.word 0xc000\n"
            elif kind == 7:
                text += f"LDI R{d}, {rng.randrange(-256,256)}\n"
            else:
                text += f"{rng.choice(['ADD','SUB','AND','OR','XOR','MOV','SLT','SLTU'])} R{d}, R{s}\n"
        yield f"random_{seed:02}", assemble(text + "HALT\n")


def main():
    directory = ROOT / "build/tests"
    directory.mkdir(parents=True, exist_ok=True)
    iverilog, vvp = tool("iverilog", "IVERILOG"), tool("vvp", "VVP")
    executable = directory / "cpu.vvp"
    subprocess.run([iverilog, "-g2005-sv", "-Wall", "-s", "tb_tiny16", "-o", str(executable),
                    "rtl/tiny16.v", "tests/tb_tiny16.v"], cwd=ROOT, check=True)
    total, cases = 0, 0
    for name, words in programs():
        program = words if isinstance(words, dict) else dict(enumerate(words))
        total += simulate(name, program, vvp, executable)
        cases += 1
    print(f"ALL PASS: {cases} programs, {total} retired instructions; registers, stores, traps and cycle counts match")


if __name__ == "__main__":
    main()
