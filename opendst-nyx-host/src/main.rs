/// opendst-nyx-shim: drives a nyx-lite VM as an OpenDST execution engine.
///
/// Protocol (stdin/stdout, same shape as the existing cold-fork engine):
///   - Parent writes one Plan JSON line to stdin per iteration.
///   - Shim writes simulation log lines (JSON) to stdout as the VM emits them.
///   - Shim writes "ready\n" to stdout after the base snapshot is taken.
///   - Shim writes "SHIM_DONE\n" after each iteration completes.
///
/// Snapshot tree:
///   Snapshots are keyed by the sequence of segment hashes observed at segment
///   boundaries. Each "segment-completed" log line carries a deterministic hash of
///   the simulation state at that boundary. The running key grows by one hash per
///   boundary. On a new plan, the shim extracts the segment hashes from the plan's
///   declared prefix segments and finds the deepest matching cached snapshot to
///   restore from, skipping work already done.
///
/// Shared memory layout (two 64 KB regions registered by the guest):
///   INPUT  (name "opendst-in"):  [4-byte LE length][Plan JSON bytes]
///   OUTPUT (name "opendst-out"): newline-terminated log lines, NUL-terminated at end of iteration
///
/// Sizes must match SharedMemory.java in the guest.
use std::collections::HashMap;
use std::fs;
use std::io::{self, BufRead, Write};
use std::sync::Arc;
use std::time::Duration;

use anyhow::{bail, Context, Result};
use nyx_lite::mem::NyxMemExtension;
use nyx_lite::snapshot::NyxSnapshot;
use nyx_lite::{ExitReason, NyxVM};

const FAILTEST:       u64 = 0x747365746c696166;
const BOOT_SNAPSHOT:  u64 = 0x706e73746f6f6273; // "sbootsnp" — deferred boot snapshot

const INPUT_BUF_SIZE: usize  = 64 * 1024;
const OUTPUT_BUF_SIZE: usize = 64 * 1024;

const ITER_TIMEOUT_MS: u64 = 120_000;
const BOOT_TIMEOUT_MS: u64 = 120_000;

/// Snapshot tree key: sequence of observed segment hashes (one per completed segment boundary).
type SnapshotKey = Vec<u32>;
/// Value: (snapshot, output_cursor) — cursor is the shim's flush position at snapshot time.
/// On restore, shim starts reading from this cursor and only zeros output from cursor onwards,
/// so the guest's ByteBuffer position (also cursor) aligns with the live buffer content.
type SnapshotCache = HashMap<SnapshotKey, (Arc<NyxSnapshot>, usize)>;

fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().collect();
    if args.len() < 2 {
        eprintln!("Usage: opendst-nyx-shim <vmconfig.json>");
        std::process::exit(1);
    }
    let config_json = fs::read_to_string(&args[1])
        .with_context(|| format!("reading vmconfig: {}", &args[1]))?;

    let mut vm = NyxVM::new("opendst-nyx-shim".to_string(), &config_json);

    let (boot_snapshot, in_vaddr, out_vaddr) = boot_to_snapshot(&mut vm)?;

    println!("ready");
    io::stdout().flush()?;

    // Snapshot tree: keyed by observed segment hash sequence.
    // Boot snapshot lives under the empty key with cursor=0 (output buffer is empty at boot).
    let mut snapshot_cache: SnapshotCache = HashMap::new();
    snapshot_cache.insert(vec![], (boot_snapshot, 0));

    let stdin = io::stdin();
    let mut line = String::new();
    loop {
        line.clear();
        if stdin.lock().read_line(&mut line)? == 0 {
            break;
        }
        let plan_json = line.trim_end_matches('\n');
        if plan_json.is_empty() { continue; }

        // Parse all segments and the prefix hashes from the plan JSON.
        let all_segments = parse_plan_segments(plan_json);
        let prefix_hashes = parse_prefix_hashes(plan_json);
        let (restore_key, resume_depth) = find_best_prefix(&snapshot_cache, &prefix_hashes);
        let (restore_snap, restore_cursor) = snapshot_cache[&restore_key].clone();

        eprintln!("[shim] snapshot-tree: restoring from depth {resume_depth}");

        vm.apply_snapshot(&restore_snap);
        write_plan(&mut vm, in_vaddr, plan_json.as_bytes())?;
        // Only zero the output from restore_cursor onwards — the bytes before that position
        // are the guest's "preamble" output (already flushed) and must survive so the guest's
        // ByteBuffer position (restore_cursor) stays consistent with the live buffer contents.
        partial_zero_output(&mut vm, out_vaddr, restore_cursor);

        let mut observed_key: SnapshotKey = restore_key.clone();
        // Deliver only the tail segments (beyond what the snapshot already covers).
        // The shim writes each one to INPUT at each RequestSnapshot hypercall.
        let tail_segments: Vec<(u64, u64)> = all_segments[resume_depth..].to_vec();
        // first_segment is always all_segments[0]: written to INPUT when Source.next()
        // issues the deferred boot snapshot, so it can re-seed from the new plan.
        let first_segment = all_segments.first().copied();
        run_iteration(&mut vm, out_vaddr, in_vaddr, &tail_segments, first_segment, restore_cursor, resume_depth, &mut observed_key, &mut snapshot_cache)?;
        println!("SHIM_DONE");
        io::stdout().flush()?;
    }

    std::process::exit(0);
}

/// Extracts the segment hashes from the plan's prefix segments (all segments except the last).
/// Returns a Vec<u32> of hashes. Segments with hash=0 (unknown) are excluded — they cannot
/// match any cache entry.
fn parse_prefix_hashes(plan_json: &str) -> Vec<u32> {
    let mut result = Vec::new();
    let Some(seg_start) = plan_json.find("\"segments\"") else { return result; };
    let after = &plan_json[seg_start..];
    let Some(arr_start) = after.find('[') else { return result; };
    let arr = &after[arr_start..];

    // Collect all segments first, then drop the last one (it's the new exploratory segment).
    let mut segments: Vec<u32> = Vec::new();
    let mut pos = 1usize;
    while pos < arr.len() {
        let c = arr.as_bytes()[pos];
        if c == b']' { break; }
        if c != b'{' { pos += 1; continue; }
        let Some(obj_end) = arr[pos..].find('}') else { break; };
        let obj = &arr[pos..pos + obj_end + 1];
        if let Some(hash) = extract_u64(obj, "hash") {
            segments.push(hash as u32);
        }
        pos += obj_end + 1;
    }

    // All but the last segment are "complete" prefix segments with known hashes.
    if segments.len() > 1 {
        for &h in &segments[..segments.len() - 1] {
            if h != 0 {
                result.push(h);
            } else {
                // Hash=0 means unknown — stop here, can't match further.
                break;
            }
        }
    }
    result
}

/// Parses all (seed, iteration) pairs from the plan's segments array.
fn parse_plan_segments(plan_json: &str) -> Vec<(u64, u64)> {
    let mut result = Vec::new();
    let Some(seg_start) = plan_json.find("\"segments\"") else { return result; };
    let after = &plan_json[seg_start..];
    let Some(arr_start) = after.find('[') else { return result; };
    let arr = &after[arr_start..];

    let mut pos = 1usize;
    while pos < arr.len() {
        let c = arr.as_bytes()[pos];
        if c == b']' { break; }
        if c != b'{' { pos += 1; continue; }
        let Some(obj_end) = arr[pos..].find('}') else { break; };
        let obj = &arr[pos..pos + obj_end + 1];
        if let (Some(seed), Some(iter)) = (extract_u64(obj, "seed"), extract_u64(obj, "until")) {
            result.push((seed, iter));
        }
        pos += obj_end + 1;
    }
    result
}

fn extract_u64(obj: &str, field: &str) -> Option<u64> {
    let key = format!("\"{}\":", field);
    let start = obj.find(&key)? + key.len();
    let rest = obj[start..].trim_start_matches(' ');
    // Allow optional leading '-' for negative Java ints serialized in JSON.
    let (rest, negative) = if rest.starts_with('-') { (&rest[1..], true) } else { (rest, false) };
    let end = rest.find(|c: char| !c.is_ascii_digit()).unwrap_or(rest.len());
    let n: i64 = rest[..end].parse().ok()?;
    Some(if negative { (-n) as u64 } else { n as u64 })
}

/// Find the deepest cached prefix whose key is a prefix of `prefix_hashes`.
fn find_best_prefix(cache: &SnapshotCache, prefix_hashes: &[u32]) -> (SnapshotKey, usize) {
    for depth in (0..=prefix_hashes.len()).rev() {
        let key: SnapshotKey = prefix_hashes[..depth].to_vec();
        if cache.contains_key(&key) {
            return (key, depth);
        }
    }
    (vec![], 0)
}

fn run_iteration(
    vm: &mut NyxVM,
    out_vaddr: u64,
    in_vaddr: u64,
    plan_segments: &[(u64, u64)],
    first_segment: Option<(u64, u64)>,
    restore_cursor: usize,
    resume_depth: usize,
    observed_key: &mut SnapshotKey,
    snapshot_cache: &mut SnapshotCache,
) -> Result<()> {
    let mut cursor: usize = restore_cursor;
    // segments_delivered counts how many segment transitions the guest has seen.
    // Starts at resume_depth because segments [0..resume_depth] were in the plan JSON.
    let mut segments_delivered = resume_depth;

    loop {
        match vm.run(Duration::from_millis(ITER_TIMEOUT_MS)) {
            ExitReason::ExecDone(_) => {
                flush_output(vm, out_vaddr, &mut cursor);
                return Ok(());
            }
            ExitReason::RequestSnapshot => {
                // Guest issued snapshot hypercall at a segment boundary.
                // 1. Flush output so segment-completed is visible before we snapshot.
                flush_output(vm, out_vaddr, &mut cursor);

                // 2. Extract the hash from the just-flushed segment-completed line
                //    to build the observed key, then take + cache the snapshot.
                let hash = extract_last_segment_hash(vm, out_vaddr, cursor);
                if let Some(h) = hash {
                    observed_key.push(h);
                    if observed_key.len() > resume_depth && !snapshot_cache.contains_key(observed_key) {
                        let snap = vm.take_snapshot();
                        eprintln!("[shim] snapshot-tree: cached at depth {} hash={h:#010x}", observed_key.len());
                        snapshot_cache.insert(observed_key.clone(), (snap, cursor));
                    }
                }

                // 3. Write the next segment to INPUT, or zero sentinel for end-of-plan.
                let next_seg_idx = segments_delivered;
                segments_delivered += 1;
                if next_seg_idx < plan_segments.len() {
                    let (seed, iteration) = plan_segments[next_seg_idx];
                    write_next_segment(vm, in_vaddr, seed, iteration);
                } else {
                    write_end_of_plan(vm, in_vaddr);
                }
                // VM resumes after the hypercall returns
            }
            ExitReason::Hypercall(BOOT_SNAPSHOT, _, _, _, _) => {
                // Deferred boot snapshot: guest issued this at the first Source.next() call,
                // after plan parsing and node init. Take the snapshot, overwrite the boot
                // entry in the cache, then write segment 0 so Source can re-seed.
                // cursor here is the guest's ByteBuffer position at snapshot time — store it
                // so future restores start reading from the same offset.
                flush_output(vm, out_vaddr, &mut cursor);
                let snap = vm.take_snapshot();
                snapshot_cache.insert(vec![], (snap, cursor));
                eprintln!("[shim] snapshot-tree: boot snapshot taken (deferred, cursor={cursor})");
                if let Some((seed, iteration)) = first_segment {
                    write_next_segment(vm, in_vaddr, seed, iteration);
                } else {
                    write_end_of_plan(vm, in_vaddr);
                }
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
                flush_output(vm, out_vaddr, &mut cursor);
                return Ok(());
            }
            _ => flush_output(vm, out_vaddr, &mut cursor),
        }
    }
}

fn flush_output(vm: &mut NyxVM, out_vaddr: u64, cursor: &mut usize) {
    let buf = vm.read_current_bytes(out_vaddr, OUTPUT_BUF_SIZE);
    let mut line_start = *cursor;
    let mut i = *cursor;

    while i < buf.len() {
        match buf[i] {
            0 => {
                if i > line_start {
                    let s = String::from_utf8_lossy(&buf[line_start..i]);
                    let s = s.as_ref();
                    print!("{s}");
                    if !s.ends_with('\n') { println!(); }
                }
                *cursor = i;
                return;
            }
            b'\n' => {
                let s = String::from_utf8_lossy(&buf[line_start..=i]);
                print!("{}", s.as_ref());
                line_start = i + 1;
            }
            _ => {}
        }
        i += 1;
    }
    *cursor = line_start;
}

/// Reads the output buffer from `cursor` forward to find the hash field of the most
/// recent "segment-completed" line. Returns it as u32.
fn extract_last_segment_hash(vm: &NyxVM, out_vaddr: u64, cursor: usize) -> Option<u32> {
    let buf = vm.read_current_bytes(out_vaddr, OUTPUT_BUF_SIZE);
    // Scan backwards from cursor for the segment-completed line.
    let text = String::from_utf8_lossy(&buf[..cursor]);
    for line in text.lines().rev() {
        if line.contains("\"segment-completed\"") {
            if let Some(h) = extract_u64(line, "hash") {
                if h != 0 { return Some(h as u32); }
            }
        }
    }
    None
}

/// Writes [8-byte seed LE][8-byte iteration LE] to the INPUT region for the next segment.
fn write_next_segment(vm: &mut NyxVM, in_vaddr: u64, seed: u64, iteration: u64) {
    let cr3 = vm.sregs().cr3;
    let vmm = vm.vmm.lock().unwrap();
    vmm.write_virtual_bytes(cr3, in_vaddr, &seed.to_le_bytes()).unwrap();
    vmm.write_virtual_bytes(cr3, in_vaddr + 8, &iteration.to_le_bytes()).unwrap();
}

/// Writes zero sentinel to INPUT to signal end-of-plan.
fn write_end_of_plan(vm: &mut NyxVM, in_vaddr: u64) {
    let cr3 = vm.sregs().cr3;
    let vmm = vm.vmm.lock().unwrap();
    vmm.write_virtual_bytes(cr3, in_vaddr, &[0u8; 16]).unwrap();
}

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

/// Zeroes the output buffer from `from_offset` to the end, leaving bytes [0..from_offset]
/// intact. This preserves the guest's "preamble" output that was captured in the snapshot,
/// so the guest's ByteBuffer position (= from_offset) stays consistent with buffer contents.
fn partial_zero_output(vm: &mut NyxVM, out_vaddr: u64, from_offset: usize) {
    if from_offset >= OUTPUT_BUF_SIZE { return; }
    let len = OUTPUT_BUF_SIZE - from_offset;
    let cr3 = vm.sregs().cr3;
    let vmm = vm.vmm.lock().unwrap();
    vmm.write_virtual_bytes(cr3, out_vaddr + from_offset as u64, &vec![0u8; len]).unwrap();
}
