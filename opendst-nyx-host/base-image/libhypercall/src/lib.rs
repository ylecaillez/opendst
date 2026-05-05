//! Native hypercall library for the opendst nyx-lite guest.
//!
//! Exports a single C function: `hypercall(num, a1, a2, a3, a4)`.
//! Called from Java via Panama FFM (no JNI).
//!
//! nyx-lite ABI:
//!   int3
//!   RAX = NYX_LITE  (0x6574696c2d78796e)
//!   R8  = num, R9 = a1, R10 = a2, R11 = a3, R12 = a4
//!
//! Feature `stub`: print to stderr instead of issuing int3 (local testing without a VM).

#[no_mangle]
pub unsafe extern "C" fn hypercall(num: u64, a1: u64, a2: u64, a3: u64, a4: u64) {
    #[cfg(not(feature = "stub"))]
    std::arch::asm!(
        "int3",
        in("rax") 0x6574696c2d78796eu64,
        in("r8")  num,
        in("r9")  a1,
        in("r10") a2,
        in("r11") a3,
        in("r12") a4,
        options(nostack, preserves_flags),
    );
    #[cfg(feature = "stub")]
    eprintln!("[hypercall-stub] num={num:#018x} a1={a1:#018x} a2={a2:#018x} a3={a3:#018x} a4={a4:#018x}");
}
