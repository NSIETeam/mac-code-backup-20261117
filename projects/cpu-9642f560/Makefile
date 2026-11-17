PYTHON ?= python3
IVERILOG ?= $(if $(wildcard .tools/iverilog/bin/iverilog),.tools/iverilog/bin/iverilog,iverilog)
VVP ?= $(if $(wildcard .tools/iverilog/bin/vvp),.tools/iverilog/bin/vvp,vvp)
YOSYS ?= $(if $(wildcard .tools/venv/bin/yowasp-yosys),.tools/venv/bin/yowasp-yosys,yosys)

.PHONY: all test demo synth clean
all: test

programs/sum.hex: programs/sum.asm tools/asm.py
	$(PYTHON) tools/asm.py $< -o $@ --depth 1024

build:
	mkdir -p build

test: demo | build
	IVERILOG=$(IVERILOG) VVP=$(VVP) $(PYTHON) tests/run_tests.py
	$(IVERILOG) -g2005-sv -Wall -s tb_reset -o build/reset.vvp rtl/tiny16.v tests/tb_reset.v
	$(VVP) build/reset.vvp

demo: programs/sum.hex | build
	$(IVERILOG) -g2005-sv -Wall -s tb_sum -o build/sum.vvp rtl/tiny16.v rtl/tiny16_soc.v tests/tb_sum.v
	$(VVP) build/sum.vvp

synth: | build
	$(YOSYS) -Q -T -l build/synth-ice40.log -p 'read_verilog rtl/tiny16.v; synth_ice40 -top tiny16 -json build/tiny16-ice40.json; check -assert; stat'

clean:
	rm -rf build
