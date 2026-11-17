`timescale 1ns/1ns

module alu_tb;
    reg [7:0] data1 = 8'd23;
    reg [7:0] data2 = 8'd34;
    reg [2:0] op;
    wire [7:0] result;

    alu dut(.data1(data1), .data2(data2), .op(op), .result(result));

    initial begin
        $dumpfile("alu.vcd");
        $dumpvars(0, alu_tb);
        $display("time   op   data1   data2   result (hex / decimal)");
        $monitor("%4t   %b   %5d   %5d   %h / %d",
                 $time, op, data1, data2, result, result);
        op = 3'd0;
        #50 op = 3'd1;
        #50 op = 3'd2;
        #50 op = 3'd3;
        #50 op = 3'd4;
        #50 op = 3'd5;
        #50 op = 3'd6;
        #50 $finish;
    end
endmodule
