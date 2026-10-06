/**
 * Disassemble and inspect entry points of insecure_commands
 */
const mod = Process.findModuleByName("libapp.so");
if (mod) {
    const offsets = [0x2b8af0, 0x2b8db0, 0x2b9274, 0x2b98e8, 0x2b9c48, 0x2b9ef4, 0x2ba39c, 0x2ba4e0];
    offsets.forEach(off => {
        let addr = mod.base.add(off);
        console.log(`\n--- Function at offset 0x${off.toString(16)} (${addr}) ---`);
        for (let i = 0; i < 12; i++) {
            try {
                const insn = Instruction.parse(addr);
                console.log(`  ${insn.address}: ${insn.mnemonic} ${insn.opStr}`);
                addr = insn.next;
            } catch (e) {
                console.log(`  ${addr}: <error parsing instruction: ${e.message}>`);
                break;
            }
        }
    });
}
