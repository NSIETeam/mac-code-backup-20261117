`default_nettype none
// Tiny16: Verilog-2001, word-addressed, synchronous one-cycle memories.
// imem_rdata MUST hold its value when imem_en=0. No variable-latency bus.
module tiny16 #(
    parameter [15:0] RESET_PC = 16'h0000
) (
    input wire clk,
    input wire reset,
    output wire imem_en,
    output wire [15:0] imem_addr,
    input wire [15:0] imem_rdata,
    output wire dmem_re,
    output wire dmem_we,
    output wire [15:0] dmem_addr,
    output wire [15:0] dmem_wdata,
    input wire [15:0] dmem_rdata,
    output reg halted,
    output reg fault
);
    // No register-file reset: software initializes registers before use.
    reg [15:0] regs [0:7];
    reg [15:0] pc;
    reg valid;
    reg load_pending;
    wire [3:0] op = imem_rdata[15:12];
    wire [2:0] rd = imem_rdata[11:9];
    wire [2:0] rs = imem_rdata[8:6];
    wire [3:0] fn = imem_rdata[3:0];
    wire [15:0] a = regs[rd];
    wire [15:0] b = regs[rs];
    wire [15:0] imm9 = {{7{imem_rdata[8]}}, imem_rdata[8:0]};
    wire [15:0] imm6 = {{10{imem_rdata[5]}}, imem_rdata[5:0]};
    wire [15:0] imm12 = {{4{imem_rdata[11]}}, imem_rdata[11:0]};

    reg [15:0] add_a, add_b;
    reg subtract;
    // One shared adder: arithmetic, comparisons, memory and branch targets.
    wire [16:0] sum = {1'b0, add_a} +
                       {1'b0, (add_b ^ {16{subtract}})} +
                       {16'b0, subtract};
    reg [15:0] result;
    reg write_reg, is_load, is_store, jump, stop, illegal;
    wire execute = valid && !halted && !reset;

    always @* begin
        add_a = a;
        add_b = b;
        subtract = 1'b0;
        if (op == 4'h0)
            subtract = (fn == 4'h1 || fn == 4'h9 || fn == 4'ha);
        else if (op == 4'h3)
            add_b = imm9;
        else if (op == 4'h5 || op == 4'h6) begin
            add_a = b;
            add_b = imm6;
        end else if (op == 4'h7 || op == 4'h8 || op == 4'h9) begin
            // While executing instruction N, pc already addresses N+1.
            add_a = pc;
            add_b = imm9;
        end else if (op == 4'ha) begin
            add_a = pc;
            add_b = imm12;
        end
    end

    always @* begin
        result = sum[15:0];
        write_reg = 1'b0;
        is_load = 1'b0;
        is_store = 1'b0;
        jump = 1'b0;
        stop = 1'b0;
        illegal = 1'b0;
        case (op)
            4'h0: begin
                write_reg = 1'b1;
                if (imem_rdata[5:4] != 2'b00) illegal = 1'b1;
                case (fn)
                    4'h0, 4'h1: result = sum[15:0];
                    4'h2: result = a & b;
                    4'h3: result = a | b;
                    4'h4: result = a ^ b;
                    4'h5: result = {a[14:0], 1'b0};
                    4'h6: result = {1'b0, a[15:1]};
                    4'h7: result = {a[15], a[15:1]};
                    4'h8: result = b;
                    4'h9: result = {15'b0, ((a[15] ^ b[15]) ? a[15] : sum[15])};
                    4'ha: result = {15'b0, !sum[16]};
                    default: illegal = 1'b1;
                endcase
            end
            4'h1: begin write_reg = 1'b1; result = imm9; end
            4'h2: begin
                write_reg = 1'b1;
                result = {imem_rdata[7:0], 8'b0};
                illegal = imem_rdata[8];
            end
            4'h3: write_reg = 1'b1;
            4'h4: begin
                write_reg = 1'b1;
                result = a | {7'b0, imem_rdata[8:0]};
            end
            4'h5: is_load = 1'b1;
            4'h6: is_store = 1'b1;
            4'h7: jump = (a == 16'b0);
            4'h8: jump = (a != 16'b0);
            4'h9: jump = a[15];
            4'ha: jump = 1'b1;
            4'hb: begin
                jump = 1'b1;
                result = a;
                illegal = (imem_rdata[8:0] != 9'b0);
            end
            4'he: illegal = (imem_rdata[11:0] != 12'b0);
            4'hf: begin
                stop = 1'b1;
                illegal = (imem_rdata[11:0] != 12'b0);
            end
            default: illegal = 1'b1;
        endcase
    end

    assign imem_addr = pc;
    // Hold the instruction output during load issue; it retains the load's rd.
    // Fetch the next instruction on the load writeback edge.
    assign imem_en = !reset && !halted &&
                     !(execute && (is_load || jump || stop || illegal));
    assign dmem_re = execute && !illegal && is_load;
    assign dmem_we = execute && !illegal && is_store;
    assign dmem_addr = sum[15:0];
    assign dmem_wdata = a;

    // Separate unreset register-file write port helps memory inference.
    always @(posedge clk) begin
        if (!reset && !halted) begin
            if (load_pending)
                regs[rd] <= dmem_rdata;
            else if (execute && !illegal && write_reg)
                regs[rd] <= result;
        end
    end

    always @(posedge clk) begin
        if (reset) begin
            pc <= RESET_PC;
            valid <= 1'b0;
            load_pending <= 1'b0;
            halted <= 1'b0;
            fault <= 1'b0;
        end else if (!halted) begin
            valid <= imem_en;
            load_pending <= dmem_re;
            if (imem_en)
                pc <= pc + 16'd1; // Dedicated incrementer preserves 1 IPC.
            if (execute && !illegal && jump)
                pc <= result;
            if (execute && (stop || illegal)) begin
                halted <= 1'b1;
                fault <= illegal;
            end
        end
    end
endmodule
`default_nettype wire
