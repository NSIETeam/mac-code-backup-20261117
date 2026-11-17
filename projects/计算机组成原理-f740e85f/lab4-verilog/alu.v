`timescale 1ns/1ns

module alu(
    input wire [7:0] data1,
    input wire [7:0] data2,
    input wire [2:0] op,
    output reg [7:0] result
);
    always @* begin
        case (op)
            3'b000: result = 8'd0;
            3'b001: result = data1 + data2;
            3'b010: result = data1 - data2;
            3'b011: result = data2 + 8'd1;
            3'b100: result = data2 - 8'd1;
            3'b101: result = data1 & data2;
            3'b110: result = data1 | data2;
            default: result = 8'bx;
        endcase
    end
endmodule
