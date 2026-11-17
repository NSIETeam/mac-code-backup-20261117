`timescale 1ns/1ps
module tb_sum;
    reg clk = 0;
    reg reset = 1;
    wire halted, fault;
    integer cycles = 0;
    always #5 clk = ~clk;
    tiny16_soc dut(clk, reset, halted, fault);
    initial begin
        $dumpfile("build/sum.vcd");
        $dumpvars(0, tb_sum);
        repeat (3) @(negedge clk);
        reset = 0;
    end
    always @(posedge clk) begin
        if (!reset) begin
            cycles = cycles + 1;
            #1;
            if (cycles > 450) $fatal(1, "sum timed out");
            if (halted) begin
                if (fault || dut.ram[0] !== 16'd5050 || cycles != 405)
                    $fatal(1, "bad result: RAM[0]=%h cycles=%0d fault=%b", dut.ram[0], cycles, fault);
                $display("PASS sum SoC: RAM[0]=5050, 305 instructions in 405 cycles");
                $finish;
            end
        end
    end
endmodule
