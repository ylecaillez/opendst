/// opendst-nyx-shim: drives a nyx-lite VM as an OpenDST execution engine.
///
/// Protocol (stdin/stdout, same shape as the existing cold-fork engine):
///   - Parent writes one Plan JSON line to stdin per iteration.
///   - Shim writes simulation log lines (JSON) to stdout as the VM emits them.
///   - Shim writes "ready\n" to stdout after the base snapshot is taken.
///   - Parent reads until it sees the "stopped" lifecycle signal, then sends the next Plan.
///
/// Shared memory layout (two 64 KB regions registered by the guest):
///   INPUT  (name "opendst-in"):  [4-byte LE length][Plan JSON bytes]
///   OUTPUT (name "opendst-out"): newline-terminated log lines, NUL-terminated at end of iteration
///
/// Sizes must match SharedMemory.java in the guest.
use std::fs;
use std::io::{self, BufRead, Write};
use std::sync::Arc;
use std::time::Duration;

use anyhow::{bail, Context, Result};
use nyx_lite::mem::NyxMemExtension;
use nyx_lite::snapshot::NyxSnapshot;
use nyx_lite::{ExitReason, NyxVM};

// FAILTEST hypercall opcode — must match guest/src/opendst/nyx/guest/Hypercall.java
// (EXECDONE/SNAPSHOT/SHAREMEM/DBGPRINT are handled by named ExitReason variants in nyx-lite)
const FAILTEST: u64 = 0x747365746c696166;

const INPUT_BUF_SIZE: usize  = 64 * 1024;
const OUTPUT_BUF_SIZE: usize = 64 * 1024;

const ITER_TIMEOUT_MS: u64 = 120_000;
const BOOT_TIMEOUT_MS: u64 = 120_000;

fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 {
        eprintln!("Usage: opendst-nyx-shim <vmconfig.json>");
        std::process::exit(1);
    }
    let config_json = fs::read_to_string(&args[1])
        .with_context(|| format!("reading vmconfig: {}", &args[1]))?;

    let mut vm = NyxVM::new("opendst-nyx-shim".to_string(), &config_json);

    // Boot until guest registers both shared memory regions and calls hypercall_snapshot.
    let (snapshot, in_vaddr, out_vaddr) = boot_to_snapshot(&mut vm)?;

    println!("ready");
    io::stdout().flush()?;

    // Iteration loop: read Plan JSON from stdin, run one simulation, emit log lines.
    let stdin = io::stdin();
    let mut line = String::new();
    loop {
        line.clear();
        if stdin.lock().read_line(&mut line)? == 0 {
            break; // parent closed stdin
        }
        let plan_json = line.trim_end_matches('\n');
        if plan_json.is_empty() { continue; }

        vm.apply_snapshot(&snapshot);
        write_plan(&mut vm, in_vaddr, plan_json.as_bytes())?;
        zero_output(&mut vm, out_vaddr);
        run_iteration(&mut vm, out_vaddr)?;
        io::stdout().flush()?;
    }

    // Bypass NyxVM drop: after the last EXECDONE the vCPU is suspended in KVM
    // VMEXIT state and its thread blocks indefinitely on cleanup. Exit directly.
    std::process::exit(0);
}

/// Runs the VM until both shared regions are registered and the guest calls hypercall_snapshot.
fn boot_to_snapshot(vm: &mut NyxVM) -> Result<(Arc<NyxSnapshot>, u64, u64)> {
    let mut in_vaddr:  Option<u64> = None;
    let mut out_vaddr: Option<u64> = None;

    loop {
        match vm.run(Duration::from_millis(BOOT_TIMEOUT_MS)) {
            ExitReason::SharedMem(name, vaddr, _size) => {
                match name.trim_end_matches('\0') {
                    "opendst-in"  => { in_vaddr  = Some(vaddr); }
                    "opendst-out" => { out_vaddr = Some(vaddr); }
                    other => eprintln!("[shim] unknown shared region: {other}"),
                }
            }
            ExitReason::RequestSnapshot => {
                let snap     = vm.take_snapshot();
                let in_addr  = in_vaddr.context("guest never registered 'opendst-in'")?;
                let out_addr = out_vaddr.context("guest never registered 'opendst-out'")?;
                return Ok((snap, in_addr, out_addr));
            }
            ExitReason::DebugPrint(msg) => eprintln!("[guest] {msg}"),
            ExitReason::Hypercall(FAILTEST, ptr, _, _, _) => {
                let raw = vm.read_cstr_current(ptr);
                bail!("guest failure during boot: {}", String::from_utf8_lossy(&raw));
            }
            ExitReason::Timeout  => bail!("VM timed out during boot"),
            ExitReason::Shutdown => bail!("VM shut down during boot"),
            other => eprintln!("[shim] unexpected exit during boot: {other:?}"),
        }
    }
}

/// Writes [4-byte LE length][json bytes] into the input shared memory region.
fn write_plan(vm: &mut NyxVM, in_vaddr: u64, plan_bytes: &[u8]) -> Result<()> {
    if 4 + plan_bytes.len() > INPUT_BUF_SIZE {
        bail!("Plan JSON too large ({} bytes)", plan_bytes.len());
    }
    let cr3 = vm.sregs().cr3;
    let vmm = vm.vmm.lock().unwrap();
    let len = plan_bytes.len() as u32;
    vmm.write_virtual_bytes(cr3, in_vaddr, &len.to_le_bytes()).unwrap();
    vmm.write_virtual_bytes(cr3, in_vaddr + 4, plan_bytes).unwrap();
    Ok(())
}

/// Writes zeros into the output shared memory region to clear prior iteration data.
fn zero_output(vm: &mut NyxVM, out_vaddr: u64) {
    let cr3 = vm.sregs().cr3;
    let vmm = vm.vmm.lock().unwrap();
    vmm.write_virtual_bytes(cr3, out_vaddr, &vec![0u8; OUTPUT_BUF_SIZE]).unwrap();
}

/// Drives the VM through one simulation iteration, flushing log lines to stdout.
fn run_iteration(vm: &mut NyxVM, out_vaddr: u64) -> Result<()> {
    let mut cursor: usize = 0;
    loop {
        match vm.run(Duration::from_millis(ITER_TIMEOUT_MS)) {
            ExitReason::ExecDone(_) => {
                flush_output(vm, out_vaddr, &mut cursor);
                return Ok(());
            }
            ExitReason::DebugPrint(msg) => eprintln!("[guest] {msg}"),
            ExitReason::Hypercall(FAILTEST, ptr, _, _, _) => {
                let raw = vm.read_cstr_current(ptr);
                eprintln!("[shim] guest failure: {}", String::from_utf8_lossy(&raw));
                return Ok(());
            }
            ExitReason::Timeout => {
                eprintln!("[shim] iteration timed out");
                return Ok(());
            }
            ExitReason::Shutdown => {
                eprintln!("[shim] VM shut down during iteration");
                return Err(anyhow::anyhow!("VM shut down"));
            }
            _ => flush_output(vm, out_vaddr, &mut cursor),
        }
    }
}

/// Reads newly written bytes from the guest output region and prints complete lines to stdout.
/// Advances `cursor`; stops at NUL (end-of-iteration sentinel).
fn flush_output(vm: &mut NyxVM, out_vaddr: u64, cursor: &mut usize) {
    let buf = vm.read_current_bytes(out_vaddr, OUTPUT_BUF_SIZE);
    let mut line_start = *cursor;
    let mut i = *cursor;
    while i < buf.len() {
        match buf[i] {
            0 => {
                if i > line_start {
                    let s = String::from_utf8_lossy(&buf[line_start..i]);
                    print!("{s}");
                    if !s.ends_with('\n') { println!(); }
                }
                *cursor = i;
                return;
            }
            b'\n' => {
                let s = String::from_utf8_lossy(&buf[line_start..=i]);
                print!("{s}");
                line_start = i + 1;
            }
            _ => {}
        }
        i += 1;
    }
    *cursor = line_start;
}
