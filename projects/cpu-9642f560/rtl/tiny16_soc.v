`default_nettype none
// Small FPGA integration example. Addresses wrap at the configured depth.
// Keep MEM_ADDR_BITS in 1..16. RAM is deliberately not reset.
module tiny16_soc #(
    parameter MEM_ADDR_BITS = 10,
    parameter ROM_FILE = "programs/sum.hex"
) (
    input wire clk,
    input wire reset,
    output wire halted,
    output wire fault
);
    wire imem_en, dmem_re, dmem_we;
    wire [15:0] imem_addr, dmem_addr, dmem_wdata;
    reg [15:0] imem_rdata, dmem_rdata;
    reg [15:0] rom [0:(1 << MEM_ADDR_BITS)-1];
    reg [15:0] ram [0:(1 << MEM_ADDR_BITS)-1];
    initial begin
        if (ROM_FILE != "") $readmemh(ROM_FILE, rom);
    end
    always @(posedge clk) begin
        if (imem_en) imem_rdata <= rom[imem_addr[MEM_ADDR_BITS-1:0]];
        if (dmem_we) ram[dmem_addr[MEM_ADDR_BITS-1:0]] <= dmem_wdata;
        else if (dmem_re) dmem_rdata <= ram[dmem_addr[MEM_ADDR_BITS-1:0]];
    end
    tiny16 cpu (
        .clk(clk), .reset(reset),
        .imem_en(imem_en), .imem_addr(imem_addr), .imem_rdata(imem_rdata),
        .dmem_re(dmem_re), .dmem_we(dmem_we), .dmem_addr(dmem_addr),
        .dmem_wdata(dmem_wdata), .dmem_rdata(dmem_rdata),
        .halted(halted), .fault(fault)
    );
endmodule
`default_nettype wire
