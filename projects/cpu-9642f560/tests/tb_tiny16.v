`timescale 1ns/1ps
module tb_tiny16;
    reg clk = 0;
    always #5 clk = ~clk;
    reg reset = 1;
    wire ie, re, we, halted, fault;
    wire [15:0] ia, da, wd;
    reg [15:0] ir, dr;
    reg [15:0] rom [0:65535];
    reg [15:0] ram [0:65535];
    reg [4095:0] rom_file, trace_file;
    integer fd, i, cycles = 0, retired = 0;
    reg retire_now;
    reg [15:0] retiring_pc, retiring_ir;
    tiny16 dut(clk, reset, ie, ia, ir, re, we, da, wd, dr, halted, fault);
    always @(posedge clk) begin
        if (ie) ir <= rom[ia];
        if (we) ram[da] <= wd;
        else if (re) dr <= ram[da];
    end
    initial begin
        if (!$value$plusargs("ROM=%s", rom_file)) $fatal(1, "missing ROM");
        if (!$value$plusargs("TRACE=%s", trace_file)) $fatal(1, "missing TRACE");
        for (i=0; i<65536; i=i+1) begin
            rom[i] = 16'hf000;
            ram[i] = (i * 73 + 19) & 65535;
        end
        $readmemh(rom_file, rom);
        fd = $fopen(trace_file, "w");
        if (!fd) $fatal(1, "cannot open trace");
        repeat (3) @(negedge clk);
        reset = 0;
    end
    always @(posedge clk) begin
        if (reset) begin
            if (ie || re || we) $fatal(1, "memory request during reset");
        end else begin
            cycles = cycles + 1;
            if (cycles > 20000) $fatal(1, "timeout");
            if (re && we) $fatal(1, "simultaneous read/write");
            if (we) $fdisplay(fd, "W %04h %04h", da, wd);
            retire_now = dut.load_pending || (dut.execute && !dut.is_load);
            retiring_pc = dut.pc - 16'd1;
            retiring_ir = ir;
            #1;
            if (retire_now) begin
                retired = retired + 1;
                $fwrite(fd, "R %04h %04h", retiring_pc, retiring_ir);
                for (i=0; i<8; i=i+1) $fwrite(fd, " %04h", dut.regs[i]);
                $fdisplay(fd, "");
            end
            if (halted) begin
                $fdisplay(fd, "END %0d %0d %0d", cycles, retired, fault);
                // A stopped CPU must not keep touching memory.
                repeat (3) begin
                    @(posedge clk);
                    #1;
                    if (ie || re || we || !halted) $fatal(1, "halt unstable");
                end
                $fclose(fd);
                $finish;
            end
        end
    end
endmodule
