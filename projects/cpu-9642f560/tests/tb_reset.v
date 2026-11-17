`timescale 1ns/1ps
module tb_reset;
    reg clk = 0, reset = 1;
    always #5 clk = ~clk;
    wire ie, re, we, halted, fault;
    wire [15:0] ia, da, wd;
    reg [15:0] ir, dr;
    reg [15:0] rom [0:15];
    reg [15:0] ram [0:15];
    integer i, writes = 0;
    tiny16 dut(clk, reset, ie, ia, ir, re, we, da, wd, dr, halted, fault);
    always @(posedge clk) begin
        if (ie) ir <= rom[ia[3:0]];
        if (we) begin ram[da[3:0]] <= wd; writes = writes + 1; end
        else if (re) dr <= ram[da[3:0]];
    end
    task hold_reset;
        begin
            reset = 1;
            #1;
            if (ie || re || we) $fatal(1, "reset did not suppress requests");
            repeat (2) @(negedge clk);
            if (dut.pc !== 0 || dut.valid || dut.load_pending || halted || fault)
                $fatal(1, "reset state incorrect");
        end
    endtask
    initial begin
        for (i=0; i<16; i=i+1) begin rom[i] = 16'hf000; ram[i] = 16'h5678; end
        rom[0] = 16'h1000; // LDI R0,0
        rom[1] = 16'h1207; // LDI R1,7
        rom[2] = 16'h5200; // LD R1,R0,0
        rom[3] = 16'h6200; // ST R1,R0,0
        repeat (3) @(negedge clk);
        reset = 0;
        wait (dut.load_pending === 1'b1);
        @(negedge clk);
        hold_reset; // cancel load writeback, preserve old R1
        if (dut.regs[1] !== 16'd7 || writes != 0)
            $fatal(1, "reset failed to cancel load");
        rom[0] = 16'h6200; // pending store must also be suppressible
        reset = 0;
        wait (we === 1'b1);
        @(negedge clk);
        hold_reset;
        if (writes != 0) $fatal(1, "store happened during reset");
        rom[0] = 16'ha003; // JMP 4
        rom[4] = 16'h6200;
        reset = 0;
        wait (dut.pc === 16'd4);
        @(negedge clk);
        hold_reset;
        if (writes != 0) $fatal(1, "wrong-path store after reset");
        rom[0] = 16'hc000; // illegal instruction
        reset = 0;
        wait (fault === 1'b1);
        @(negedge clk);
        hold_reset;
        rom[0] = 16'hf000;
        reset = 0;
        wait (halted === 1'b1);
        #1;
        if (fault || writes != 0) $fatal(1, "restart failed");
        $display("PASS reset: load cancellation, store suppression, branch flush, fault recovery");
        $finish;
    end
    initial begin
        #2000;
        $fatal(1, "reset test timeout");
    end
endmodule
