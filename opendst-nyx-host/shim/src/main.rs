/// opendst-nyx-shim: drives a nyx-lite VM as an OpenDST execution engine.
///
/// Protocol (stdin/stdout):
///   - Parent writes one Plan JSON line to stdin per iteration.
///   - Shim writes simulation log lines (JSON) to stdout as the VM emits them.
///   - Shim writes checkpoint JSON lines when snapshots are taken (see below).
///   - Shim writes "ready\n" to stdout after the base snapshot is taken.
///   - Shim writes "SHIM_DONE\n" after each iteration completes.
///
/// Snapshot cache:
///   Snapshots are keyed by UUID ([u8;16]). The initial snapshot lives under
///   the nil UUID (all zeros). When the guest issues a RequestSnapshot hypercall,
///   the shim assigns a fresh UUID, caches the snapshot, and emits:
///     {"source":"simulator","type":"checkpoint","id":"<uuid>","iteration":<n>,"hash":<h>}
///   The orchestrator records this UUID. On the next plan the checkpoint field
///   carries the UUID; the shim does a direct cache lookup — no hash-based identity.
///
/// Plan resume logic:
///   The plan always carries the complete segment sequence from iteration 0.
///   The shim finds the first segment whose `until > checkpoint_iteration` —
///   that is the first segment not yet covered by the checkpoint — and delivers
///   only those tail segments to the guest at each boundary hypercall.
///
/// Shared memory layout:
///   INPUT  (name "opendst-in"):  [4-byte LE length][Plan JSON bytes]
///                                or [seed 8B LE][until 8B LE] at segment delivery
///   OUTPUT (name "opendst-out"): newline-terminated log lines, NUL-terminated
///
/// Sizes must match SharedMemory.java in the guest.
use std::collections::HashMap;
use std::fs;
use std::io::{self, BufRead, BufReader, BufWriter, Read, Write};
use std::panic;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use anyhow::{bail, Context, Result};
use nyx_lite::mem::{GetMem, NyxMemExtension};
use nyx_lite::snapshot::{MemorySnapshot, NyxSnapshot};
use nyx_lite::vm_continuation_statemachine::VMContinuationState;
use nyx_lite::{ExitReason, NyxVM};
use vmm::device_manager::persist::DeviceStates;
use vmm::devices::virtio::block::device::Block;
use vmm::devices::virtio::block::persist::BlockState;
use vmm::devices::virtio::block::virtio::io::cow_io::CowCache;
use vmm::devices::virtio::device::DeviceState;
use vmm::devices::virtio::persist::{MmioTransportState, VirtioDeviceState};
use vmm::persist::MicrovmState;
use vmm::snapshot::{Persist, Snapshot};
use vmm::vstate::memory::{
    Bytes, GuestAddress, GuestMemory, GuestMemoryRegion, MemoryRegionAddress,
};
use vmm::vstate::vcpu::VcpuState;
use vmm::vstate::vm::VmState;

const FAILTEST: u64 = 0x747365746c696166;
/// "segbndry" LE — matches Hypercall.SEGMENT_BOUNDARY in the guest.
const SEGMENT_BOUNDARY_HC: u64 = 0x7972646e62676573;

const INPUT_BUF_SIZE: usize = 64 * 1024;
const OUTPUT_BUF_SIZE: usize = 256 * 1024;

const ITER_TIMEOUT_MS: u64 = 120_000;
const BOOT_TIMEOUT_MS: u64 = 120_000;

/// UUID bytes — 16 bytes. Nil UUID = [0u8; 16] = initial snapshot.
type CheckpointId = [u8; 16];

// ─── Snapshot store ────────────────────────────────────────────────────────

/// Manages snapshot storage. When `disk_dir` is set, every non-boot snapshot offloads
/// its VM memory pages (incremental dirty pages) to disk to reduce RAM usage. The
/// `Arc<CowCache>` from **each** block device in the snapshot is always kept in memory —
/// one Arc per device — because `CowCacheTree::saved` only holds `Weak` references, and
/// following a dead Weak during a block read causes a panic in nyx-lite.
struct SnapshotStore {
    /// Boot snapshot (nil UUID) — always in memory.
    boot_snap: Arc<NyxSnapshot>,
    /// In-memory snapshots (used when no disk_dir is set, or for the boot snap).
    mem: HashMap<CheckpointId, (Arc<NyxSnapshot>, usize, u64)>,
    /// If set, non-boot snapshots write their page data here as `<uuid-hex>.snap`.
    disk_dir: Option<PathBuf>,
    /// Index for disk snapshots: uuid → (output_cursor, checkpoint_iteration, parent_id).
    /// Pages stored on disk are only the direct delta of this snapshot (not materialized).
    disk_index: HashMap<CheckpointId, (usize, u64, CheckpointId)>,
    /// CowCache Arcs kept alive for disk snapshots so the Weak in CowCacheTree::saved
    /// never expires while the snapshot is live in our index.
    /// One Arc per block device (indexed by device order), keyed by checkpoint UUID.
    cow_arcs: HashMap<CheckpointId, Vec<Arc<CowCache>>>,
    /// Fallback CowCache Arcs used in fresh shim processes where cow_arcs is empty.
    /// One entry per block device (in device order).
    boot_cow_arcs: Vec<Arc<CowCache>>,
}

impl SnapshotStore {
    fn new(boot_snap: Arc<NyxSnapshot>, disk_dir: Option<PathBuf>) -> Self {
        let mut mem = HashMap::new();
        mem.insert(nil_uuid(), (boot_snap.clone(), 0, 0));
        Self {
            boot_snap,
            mem,
            disk_dir,
            disk_index: HashMap::new(),
            cow_arcs: HashMap::new(),
            boot_cow_arcs: Vec::new(),
        }
    }

    fn new_with_boot_cow(
        boot_snap: Arc<NyxSnapshot>,
        disk_dir: Option<PathBuf>,
        boot_cow_arcs: Vec<Arc<CowCache>>,
    ) -> Self {
        let mut store = Self::new(boot_snap, disk_dir);
        store.boot_cow_arcs = boot_cow_arcs;
        store
    }

    fn get(&mut self, id: &CheckpointId) -> Option<(Arc<NyxSnapshot>, usize, u64)> {
        // Boot snap — always in memory.
        if id == &nil_uuid() {
            return self.mem.get(id).cloned();
        }
        // Try in-memory first.
        if let Some(entry) = self.mem.get(id) {
            return Some(entry.clone());
        }
        // Try disk.
        if let Some(&(cursor, snap_iter, parent_id)) = self.disk_index.get(id).copied().as_ref() {
            let dir = self.disk_dir.as_ref()?;
            let path = disk_snap_path(dir, id);
            // Use the per-snapshot cow_arcs if available (same-process snapshots).
            // Fall back to boot_cow_arcs for cross-process loads — one Arc per device.
            let cow_arcs: Vec<Arc<CowCache>> = if let Some(arcs) = self.cow_arcs.get(id) {
                arcs.clone()
            } else if !self.boot_cow_arcs.is_empty() {
                self.boot_cow_arcs.clone()
            } else {
                return None;
            };
            // Recursively load the parent snapshot so we can chain incrementals correctly.
            let parent_snap = if parent_id == nil_uuid() {
                self.boot_snap.clone()
            } else {
                match self.get(&parent_id) {
                    Some((arc, _, _)) => arc,
                    None => {
                        eprintln!(
                            "[shim] parent {} not found for {}, falling back to boot",
                            format_uuid(&parent_id),
                            format_uuid(id)
                        );
                        self.boot_snap.clone()
                    }
                }
            };
            match load_snapshot_from_disk(&path, parent_snap, cow_arcs) {
                Ok(snap) => {
                    let arc = Arc::new(snap);
                    return Some((arc, cursor, snap_iter));
                }
                Err(e) => {
                    eprintln!(
                        "[shim] failed to load snapshot from disk {}: {e}",
                        path.display()
                    );
                    return None;
                }
            }
        }
        None
    }

    /// `parent_id` is the CheckpointId of the snapshot's parent (i.e. the checkpoint
    /// the current plan was restored from). It is stored in the index so that when
    /// loading from disk we can reconstruct the incremental parent chain.
    fn insert(
        &mut self,
        id: CheckpointId,
        snap: Arc<NyxSnapshot>,
        cursor: usize,
        snap_iter: u64,
        parent_id: CheckpointId,
    ) {
        if let Some(dir) = &self.disk_dir {
            let path = disk_snap_path(dir, &id);
            // Extract a CowCache Arc for EVERY block device before saving to disk.
            // All Arcs must stay alive so the Weak in CowCacheTree::saved remains valid
            // for each device — not just the first one.
            let cow_arc_vec: Vec<Arc<CowCache>> = snap
                .state
                .device_states
                .block_devices
                .iter()
                .filter_map(|b| {
                    if let BlockState::Virtio(ref vbs) = b.device_state {
                        Some(vbs.cow_state.clone())
                    } else {
                        None
                    }
                })
                .collect();
            match save_snapshot_to_disk(&path, &snap, cursor, snap_iter) {
                Ok(()) => {
                    self.disk_index.insert(id, (cursor, snap_iter, parent_id));
                    if !cow_arc_vec.is_empty() {
                        self.cow_arcs.insert(id, cow_arc_vec);
                    }
                    // Persist the index so a fresh shim process can find this checkpoint.
                    self.persist_index();
                    // Do NOT keep the full Arc<NyxSnapshot> in memory.
                    return;
                }
                Err(e) => {
                    eprintln!(
                        "[shim] disk save failed for {}, falling back to memory: {e}",
                        path.display()
                    );
                    // Fall through to in-memory storage.
                }
            }
        }
        self.mem.insert(id, (snap, cursor, snap_iter));
    }

    /// Writes `disk_index` to `<disk_dir>/index.bin`.
    ///
    /// Binary format: count(u64 LE) then for each entry:
    ///   id(16B) + cursor(u64 LE) + snap_iter(u64 LE) + parent_id(16B)
    ///
    /// Called after every successful disk insert so a fresh shim process can
    /// reload the index and find checkpoints saved by previous shim instances.
    fn persist_index(&self) {
        let dir = match &self.disk_dir {
            Some(d) => d.clone(),
            None => return,
        };
        let path = dir.join("index.bin");
        let res = (|| -> Result<()> {
            let mut f = fs::File::create(&path).context("create index.bin")?;
            write_u64(&mut f, self.disk_index.len() as u64)?;
            for (id, &(cursor, snap_iter, ref parent_id)) in &self.disk_index {
                f.write_all(id).context("write id")?;
                write_u64(&mut f, cursor as u64)?;
                write_u64(&mut f, snap_iter)?;
                f.write_all(parent_id).context("write parent_id")?;
            }
            Ok(())
        })();
        if let Err(e) = res {
            eprintln!("[shim] warning: failed to persist snapshot index: {e}");
        }
    }

    /// Reads `<disk_dir>/index.bin` (if it exists) and populates `disk_index`.
    ///
    /// Must be called after constructing the store in a fresh shim process so
    /// checkpoints saved by previous iterations are reachable via `get()`.
    fn load_disk_index(&mut self) {
        let dir = match &self.disk_dir {
            Some(d) => d.clone(),
            None => return,
        };
        let path = dir.join("index.bin");
        if !path.exists() {
            return;
        }
        let res = (|| -> Result<()> {
            let mut f = fs::File::open(&path).context("open index.bin")?;
            let count = read_u64(&mut f)? as usize;
            for _ in 0..count {
                let mut id = [0u8; 16];
                f.read_exact(&mut id).context("read id")?;
                let cursor = read_u64(&mut f)? as usize;
                let snap_iter = read_u64(&mut f)?;
                let mut parent_id = [0u8; 16];
                f.read_exact(&mut parent_id).context("read parent_id")?;
                self.disk_index.insert(id, (cursor, snap_iter, parent_id));
            }
            Ok(())
        })();
        match res {
            Ok(()) => {}
            Err(e) => eprintln!("[shim] warning: failed to load snapshot index: {e}"),
        }
    }
}

// ─── Snapshot serialisation helpers ────────────────────────────────────────

fn disk_snap_path(dir: &Path, id: &CheckpointId) -> PathBuf {
    dir.join(format!("{}.snap", format_uuid(id)))
}

/// Encode a `VMContinuationState` as a single byte.
fn cont_state_to_u8(s: &VMContinuationState) -> u8 {
    match s {
        VMContinuationState::Main => 0,
        VMContinuationState::ForceSingleStep => 1,
        VMContinuationState::EmulateHypercall => 2,
        VMContinuationState::ForceSingleStepInjectBPs => 3,
    }
}

fn cont_state_from_u8(b: u8) -> VMContinuationState {
    match b {
        0 => VMContinuationState::Main,
        1 => VMContinuationState::ForceSingleStep,
        2 => VMContinuationState::EmulateHypercall,
        3 => VMContinuationState::ForceSingleStepInjectBPs,
        _ => VMContinuationState::Main,
    }
}

fn write_u64<W: Write>(w: &mut W, v: u64) -> Result<()> {
    w.write_all(&v.to_le_bytes()).context("write u64")
}

fn read_u64<R: Read>(r: &mut R) -> Result<u64> {
    let mut buf = [0u8; 8];
    r.read_exact(&mut buf).context("read u64")?;
    Ok(u64::from_le_bytes(buf))
}

/// Binary format (v3 — incremental delta only, not materialized):
///   cursor(u64 LE) + snap_iter(u64 LE) + tsc(u64 LE) + cont_state(u8)
///   vcpu_state_len(u64 LE) + vcpu_state(bincode)
///   vm_state_len(u64 LE)   + vm_state(bincode)
///   block_count(u64 LE)
///   for each block:
///     device_id_len(u64 LE) + device_id(UTF-8)
///     transport_state_len(u64 LE) + transport_state(bincode)
///     cow_id(u32 LE)
///     virtio_state_len(u64 LE) + virtio_state(bincode)
///   page_count(u64 LE) + [paddr(u64 LE) + page(4096 bytes)]*
///
/// Pages are the direct incremental delta of this snapshot only (not the full
/// materialized chain). The parent chain is reconstructed from disk_index on load.
fn save_snapshot_to_disk(
    path: &Path,
    snap: &Arc<NyxSnapshot>,
    cursor: usize,
    snap_iter: u64,
) -> Result<()> {
    // Extract direct delta pages only — do NOT walk the parent chain.
    let pages = match &snap.memory {
        MemorySnapshot::Incremental(map) => map,
        MemorySnapshot::Base(_) => bail!("cannot save a base snapshot to disk"),
    };
    let raw = fs::File::create(path).with_context(|| format!("create {}", path.display()))?;
    let mut f = BufWriter::with_capacity(256 * 1024, raw);

    write_u64(&mut f, cursor as u64)?;
    write_u64(&mut f, snap_iter)?;
    write_u64(&mut f, snap.tsc)?;
    f.write_all(&[cont_state_to_u8(&snap.continuation_state)])
        .context("write cont_state")?;

    // vcpu_states[0]
    let vcpu_state = snap
        .state
        .vcpu_states
        .first()
        .context("no vcpu states in snapshot")?;
    let mut vcpu_buf: Vec<u8> = Vec::new();
    Snapshot::serialize(&mut vcpu_buf, vcpu_state).context("serialize VcpuState")?;
    write_u64(&mut f, vcpu_buf.len() as u64)?;
    f.write_all(&vcpu_buf).context("write vcpu_state")?;

    // vm_state
    let mut vm_buf: Vec<u8> = Vec::new();
    Snapshot::serialize(&mut vm_buf, &snap.state.vm_state).context("serialize VmState")?;
    write_u64(&mut f, vm_buf.len() as u64)?;
    f.write_all(&vm_buf).context("write vm_state")?;

    // block devices
    let blocks = &snap.state.device_states.block_devices;
    write_u64(&mut f, blocks.len() as u64)?;
    for block in blocks {
        // device_id
        let id_bytes = block.device_id.as_bytes();
        write_u64(&mut f, id_bytes.len() as u64)?;
        f.write_all(id_bytes).context("write device_id")?;

        // transport_state
        let mut transport_buf: Vec<u8> = Vec::new();
        Snapshot::serialize(&mut transport_buf, &block.transport_state)
            .context("serialize MmioTransportState")?;
        write_u64(&mut f, transport_buf.len() as u64)?;
        f.write_all(&transport_buf)
            .context("write transport_state")?;

        // cow_id + virtio_state — extracted from BlockState::Virtio
        match &block.device_state {
            BlockState::Virtio(vbs) => {
                f.write_all(&vbs.cow_state.id.to_le_bytes())
                    .context("write cow_id")?;
                let mut virt_buf: Vec<u8> = Vec::new();
                Snapshot::serialize(&mut virt_buf, &vbs.virtio_state)
                    .context("serialize VirtioDeviceState")?;
                write_u64(&mut f, virt_buf.len() as u64)?;
                f.write_all(&virt_buf).context("write virtio_state")?;
            }
            BlockState::VhostUser(_) => {
                bail!("VhostUser block devices are not supported for disk snapshots");
            }
        }
    }

    // pages
    write_u64(&mut f, pages.len() as u64)?;
    for (paddr, page) in pages {
        write_u64(&mut f, *paddr)?;
        f.write_all(page).context("write page bytes")?;
    }
    Ok(())
}

fn read_u32<R: Read>(r: &mut R) -> Result<u32> {
    let mut buf = [0u8; 4];
    r.read_exact(&mut buf).context("read u32")?;
    Ok(u32::from_le_bytes(buf))
}

/// Reconstruct a snapshot from disk as an incremental child of `parent_snap`.
/// Only the fields read by apply_snapshot are restored; everything else is defaulted.
/// `cow_arcs` holds one live `Arc<CowCache>` per block device (in device order).
/// Each Arc must be passed back so the reconstructed VirtioBlockState holds the same
/// Arc that the live `CowCacheTree::saved` map references via a Weak pointer.
fn load_snapshot_from_disk(
    path: &Path,
    parent_snap: Arc<NyxSnapshot>,
    cow_arcs: Vec<Arc<CowCache>>,
) -> Result<NyxSnapshot> {
    let raw = fs::File::open(path).with_context(|| format!("open {}", path.display()))?;
    let mut f = BufReader::with_capacity(256 * 1024, raw);

    let _cursor = read_u64(&mut f)?;
    let _snap_iter = read_u64(&mut f)?;
    let tsc = read_u64(&mut f)?;
    let mut cont_byte = [0u8; 1];
    f.read_exact(&mut cont_byte).context("read cont_state")?;
    let continuation_state = cont_state_from_u8(cont_byte[0]);

    // vcpu_state
    let vcpu_len = read_u64(&mut f)? as usize;
    let mut vcpu_buf = vec![0u8; vcpu_len];
    f.read_exact(&mut vcpu_buf).context("read vcpu_state")?;
    let vcpu_state: VcpuState =
        Snapshot::deserialize(&mut vcpu_buf.as_slice()).context("deserialize VcpuState")?;

    // vm_state
    let vm_len = read_u64(&mut f)? as usize;
    let mut vm_buf = vec![0u8; vm_len];
    f.read_exact(&mut vm_buf).context("read vm_state")?;
    let vm_state: VmState =
        Snapshot::deserialize(&mut vm_buf.as_slice()).context("deserialize VmState")?;

    // block devices
    let block_count = read_u64(&mut f)? as usize;
    let mut block_devices = Vec::with_capacity(block_count);
    for block_idx in 0..block_count {
        // device_id
        let id_len = read_u64(&mut f)? as usize;
        let mut id_buf = vec![0u8; id_len];
        f.read_exact(&mut id_buf).context("read device_id")?;
        let device_id = String::from_utf8(id_buf).context("device_id utf8")?;

        // transport_state
        let transport_len = read_u64(&mut f)? as usize;
        let mut transport_buf = vec![0u8; transport_len];
        f.read_exact(&mut transport_buf)
            .context("read transport_state")?;
        let transport_state: MmioTransportState =
            Snapshot::deserialize(&mut transport_buf.as_slice())
                .context("deserialize MmioTransportState")?;

        // cow_id + virtio_state
        let _cow_id = read_u32(&mut f)?; // not needed: we use the live cow_arc instead
        let virt_len = read_u64(&mut f)? as usize;
        let mut virt_buf = vec![0u8; virt_len];
        f.read_exact(&mut virt_buf).context("read virtio_state")?;
        let virtio_state: VirtioDeviceState = Snapshot::deserialize(&mut virt_buf.as_slice())
            .context("deserialize VirtioDeviceState")?;

        // VirtioBlockState has private fields; clone from boot snapshot and set the two
        // public fields. Use the live cow_arc for THIS device so CowCacheTree::saved's
        // Weak stays valid. Index by block_idx (same order as serialised).
        let mut root = &parent_snap;
        while root.parent.is_some() {
            root = root.parent.as_ref().unwrap();
        }
        let mut boot_block = root
            .state
            .device_states
            .block_devices
            .iter()
            .find(|b| b.device_id == device_id)
            .with_context(|| format!("block device '{device_id}' not found in boot snapshot"))?
            .clone();
        if let BlockState::Virtio(ref mut vbs) = boot_block.device_state {
            if let Some(ca) = cow_arcs.get(block_idx) {
                vbs.cow_state = ca.clone();
            }
            vbs.virtio_state = virtio_state;
        }
        boot_block.transport_state = transport_state;
        block_devices.push(boot_block);
    }

    // pages
    let page_count = read_u64(&mut f)? as usize;
    let mut pages: HashMap<u64, Vec<u8>> = HashMap::with_capacity(page_count);
    for _ in 0..page_count {
        let paddr = read_u64(&mut f)?;
        let mut page = vec![0u8; 4096];
        f.read_exact(&mut page).context("read page")?;
        pages.insert(paddr, page);
    }

    let state = MicrovmState {
        vcpu_states: vec![vcpu_state],
        vm_state,
        device_states: DeviceStates {
            block_devices,
            ..DeviceStates::default()
        },
        ..MicrovmState::default()
    };

    let depth = parent_snap.depth + 1;
    let snap = NyxSnapshot {
        parent: Some(parent_snap),
        depth,
        memory: MemorySnapshot::Incremental(pages),
        state,
        tsc,
        continuation_state,
    };
    Ok(snap)
}

/// `apply_snapshot` patches device registers but does NOT call `activate()` on the live
/// Block struct.  In a fresh shim process the device starts as `DeviceState::Inactive`;
/// without this fix any virtio I/O (e.g. JVM lazy class loading) causes a panic at
/// `block/virtio/device.rs` → `self.device_state.mem().unwrap()`.
///
/// This helper must be called after every `vm.apply_snapshot(...)` in the shim.
fn ensure_block_devices_activated(vm: &mut NyxVM) {
    let mem = vm.vmm.lock().unwrap().get_mem().clone();
    for block_dev in &vm.block_devices {
        let mut blk = block_dev.lock().unwrap();
        if let Block::Virtio(ref mut virt_blk) = *blk {
            if !virt_blk.device_state.is_activated() {
                virt_blk.device_state = DeviceState::Activated(mem.clone());
            }
        }
    }
}

fn nil_uuid() -> CheckpointId {
    [0u8; 16]
}

// ─── Boot snapshot persistence ──────────────────────────────────────────────

const BOOT_MAGIC: &[u8; 8] = b"ODSTBT01";
const BOOT_PAGE_SIZE: usize = 4096;

/// Saves the boot snapshot and the shared-memory virtual addresses to
/// `<dir>/boot.snap` so a fresh shim process can restore it without
/// re-running the full guest boot sequence.
///
/// Memory is saved as a list of non-zero 4KB pages keyed by physical address,
/// matching the Incremental format used by `load_snapshot_from_disk`.
fn save_boot_to_disk(
    dir: &Path,
    in_vaddr: u64,
    out_vaddr: u64,
    snap: &Arc<NyxSnapshot>,
) -> Result<()> {
    let path = dir.join("boot.snap");
    let pages = match &snap.memory {
        MemorySnapshot::Base(vec) => vec,
        MemorySnapshot::Incremental(_) => bail!("expected Base snapshot for boot save"),
    };
    let raw = fs::File::create(&path).with_context(|| format!("create {}", path.display()))?;
    let mut f = BufWriter::with_capacity(256 * 1024, raw);
    f.write_all(BOOT_MAGIC).context("write magic")?;
    write_u64(&mut f, in_vaddr)?;
    write_u64(&mut f, out_vaddr)?;
    write_u64(&mut f, snap.tsc)?;
    f.write_all(&[cont_state_to_u8(&snap.continuation_state)])
        .context("write cont_state")?;

    // vcpu_state
    let vcpu_state = snap.state.vcpu_states.first().context("no vcpu states")?;
    let mut vcpu_buf: Vec<u8> = Vec::new();
    Snapshot::serialize(&mut vcpu_buf, vcpu_state).context("serialize VcpuState")?;
    write_u64(&mut f, vcpu_buf.len() as u64)?;
    f.write_all(&vcpu_buf).context("write vcpu_state")?;

    // vm_state
    let mut vm_buf: Vec<u8> = Vec::new();
    Snapshot::serialize(&mut vm_buf, &snap.state.vm_state).context("serialize VmState")?;
    write_u64(&mut f, vm_buf.len() as u64)?;
    f.write_all(&vm_buf).context("write vm_state")?;

    // block devices (same layout as v3 incremental format)
    let blocks = &snap.state.device_states.block_devices;
    write_u64(&mut f, blocks.len() as u64)?;
    for block in blocks {
        let id_bytes = block.device_id.as_bytes();
        write_u64(&mut f, id_bytes.len() as u64)?;
        f.write_all(id_bytes).context("write device_id")?;
        let mut transport_buf: Vec<u8> = Vec::new();
        Snapshot::serialize(&mut transport_buf, &block.transport_state)
            .context("serialize transport")?;
        write_u64(&mut f, transport_buf.len() as u64)?;
        f.write_all(&transport_buf).context("write transport")?;
        match &block.device_state {
            BlockState::Virtio(vbs) => {
                f.write_all(&vbs.cow_state.id.to_le_bytes())
                    .context("write cow_id")?;
                let mut virt_buf: Vec<u8> = Vec::new();
                Snapshot::serialize(&mut virt_buf, &vbs.virtio_state)
                    .context("serialize virtio")?;
                write_u64(&mut f, virt_buf.len() as u64)?;
                f.write_all(&virt_buf).context("write virtio_state")?;
            }
            BlockState::VhostUser(_) => bail!("VhostUser not supported for boot snapshot"),
        }
    }

    // memory: save non-zero pages keyed by physical address
    let mut non_zero: Vec<(u64, &[u8])> = Vec::new();
    let mut offset = 0usize;
    while offset + BOOT_PAGE_SIZE <= pages.len() {
        let page = &pages[offset..offset + BOOT_PAGE_SIZE];
        if page.iter().any(|&b| b != 0) {
            non_zero.push((offset as u64, page));
        }
        offset += BOOT_PAGE_SIZE;
    }
    write_u64(&mut f, non_zero.len() as u64)?;
    for (paddr, page) in &non_zero {
        write_u64(&mut f, *paddr)?;
        f.write_all(page).context("write page")?;
    }
    Ok(())
}

/// Extracts CowCache Arcs from all block devices of a snapshot (one per device, in order).
fn extract_cow_arcs(snap: &Arc<NyxSnapshot>) -> Vec<Arc<CowCache>> {
    snap.state
        .device_states
        .block_devices
        .iter()
        .filter_map(|b| {
            if let BlockState::Virtio(ref vbs) = b.device_state {
                Some(vbs.cow_state.clone())
            } else {
                None
            }
        })
        .collect()
}

/// Loads the boot snapshot from `<dir>/boot.snap`, constructs it as a Base
/// snapshot and applies it to the VM.  Returns `(in_vaddr, out_vaddr,
/// boot_snap, cow_arc)` or `None` if the file does not exist.
///
/// We avoid `take_snapshot()` entirely: instead we call
/// `mmio_device_manager.save()` directly to trigger `CowCacheTree::snapshot()`
/// (populating `saved[0]` for future `reset_to(0)` calls) and to obtain the
/// `Arc<CowCache>`.  We then reconstruct boot_snap as a Base (flat-Vec)
/// snapshot, set `active_snapshot` directly, and call `apply_snapshot` which
/// hits the fast_path (same Arc) — no full RAM read, no KVM save-state ioctls.
fn load_and_apply_boot_from_disk(
    dir: &Path,
    vm: &mut NyxVM,
) -> Result<Option<(u64, u64, Arc<NyxSnapshot>, Vec<Arc<CowCache>>)>> {
    let path = dir.join("boot.snap");
    if !path.exists() {
        return Ok(None);
    }

    // Trigger cow.snapshot() (registers saved[0]) and grab the CowCache Arc for each device.
    let init_device_states = vm.vmm.lock().unwrap().mmio_device_manager.save();
    let init_cow_arcs: Vec<Arc<CowCache>> = init_device_states
        .block_devices
        .iter()
        .filter_map(|b| {
            if let BlockState::Virtio(ref vbs) = b.device_state {
                Some(vbs.cow_state.clone())
            } else {
                None
            }
        })
        .collect();

    // Total guest RAM in bytes — needed to allocate the Base snapshot Vec.
    let ram_size: usize = vm
        .vmm
        .lock()
        .unwrap()
        .guest_memory()
        .iter()
        .map(|r| r.len() as usize)
        .sum();

    let raw = fs::File::open(&path).with_context(|| format!("open {}", path.display()))?;
    let mut f = BufReader::with_capacity(256 * 1024, raw);

    // Verify magic
    let mut magic = [0u8; 8];
    f.read_exact(&mut magic).context("read magic")?;
    if &magic != BOOT_MAGIC {
        bail!("boot.snap has wrong magic — stale file?");
    }

    let in_vaddr = read_u64(&mut f)?;
    let out_vaddr = read_u64(&mut f)?;
    let tsc = read_u64(&mut f)?;
    let mut cont_byte = [0u8; 1];
    f.read_exact(&mut cont_byte).context("read cont_state")?;
    let continuation_state = cont_state_from_u8(cont_byte[0]);

    // vcpu_state
    let vcpu_len = read_u64(&mut f)? as usize;
    let mut vcpu_buf = vec![0u8; vcpu_len];
    f.read_exact(&mut vcpu_buf).context("read vcpu_state")?;
    let vcpu_state: VcpuState =
        Snapshot::deserialize(&mut vcpu_buf.as_slice()).context("deserialize VcpuState")?;

    // vm_state
    let vm_len = read_u64(&mut f)? as usize;
    let mut vm_buf = vec![0u8; vm_len];
    f.read_exact(&mut vm_buf).context("read vm_state")?;
    let vm_state: VmState =
        Snapshot::deserialize(&mut vm_buf.as_slice()).context("deserialize VmState")?;

    // block devices — same logic as load_snapshot_from_disk
    let block_count = read_u64(&mut f)? as usize;
    let mut block_devices = Vec::with_capacity(block_count);
    for block_idx in 0..block_count {
        let id_len = read_u64(&mut f)? as usize;
        let mut id_buf = vec![0u8; id_len];
        f.read_exact(&mut id_buf).context("read device_id")?;
        let device_id = String::from_utf8(id_buf).context("device_id utf8")?;

        let transport_len = read_u64(&mut f)? as usize;
        let mut transport_buf = vec![0u8; transport_len];
        f.read_exact(&mut transport_buf).context("read transport")?;
        let transport_state: MmioTransportState =
            Snapshot::deserialize(&mut transport_buf.as_slice())
                .context("deserialize transport")?;

        let _cow_id = read_u32(&mut f)?;
        let virt_len = read_u64(&mut f)? as usize;
        let mut virt_buf = vec![0u8; virt_len];
        f.read_exact(&mut virt_buf).context("read virtio")?;
        let virtio_state: VirtioDeviceState =
            Snapshot::deserialize(&mut virt_buf.as_slice()).context("deserialize virtio")?;

        // Use the fresh VM's CowCache for each device by index so that the Weak in
        // CowCacheTree::saved stays valid for every device, not just the first.
        let mut boot_block = init_device_states
            .block_devices
            .iter()
            .find(|b| b.device_id == device_id)
            .with_context(|| format!("block device '{device_id}' not found in init_device_states"))?
            .clone();
        if let BlockState::Virtio(ref mut vbs) = boot_block.device_state {
            if let Some(ca) = init_cow_arcs.get(block_idx) {
                vbs.cow_state = ca.clone();
            }
            vbs.virtio_state = virtio_state;
        }
        boot_block.transport_state = transport_state;
        block_devices.push(boot_block);
    }

    // Read pages from the snap file into the Base Vec and into guest memory in a single
    // pass so each page is hot in cache when written to both destinations.  Pages are
    // stored in ascending address order by save_boot_to_disk, so consecutive pages in
    // the file are also consecutive in address space — we detect and coalesce runs to
    // cut write_slice calls from ~37K down to the number of contiguous runs.
    let page_count = read_u64(&mut f)? as usize;
    let mut mem = vec![0u8; ram_size];

    {
        let vmm_guard = vm.vmm.lock().unwrap();
        let region = vmm_guard
            .guest_memory()
            .find_region(GuestAddress(0))
            .expect("guest RAM region at GPA 0");

        let mut run_start: usize = 0;
        let mut run_end: usize = 0;

        let flush_run = |start: usize, end: usize, mem: &[u8]| {
            if start < end {
                region
                    .write_slice(&mem[start..end], MemoryRegionAddress(start as u64))
                    .expect("write boot run to guest memory");
            }
        };

        for _ in 0..page_count {
            let paddr = read_u64(&mut f)? as usize;
            f.read_exact(&mut mem[paddr..paddr + BOOT_PAGE_SIZE])
                .context("read page")?;
            if paddr == run_end {
                run_end = paddr + BOOT_PAGE_SIZE;
            } else {
                flush_run(run_start, run_end, &mem);
                run_start = paddr;
                run_end = paddr + BOOT_PAGE_SIZE;
            }
        }
        flush_run(run_start, run_end, &mem);
    }

    let state = MicrovmState {
        vcpu_states: vec![vcpu_state],
        vm_state,
        device_states: DeviceStates {
            block_devices,
            ..DeviceStates::default()
        },
        ..MicrovmState::default()
    };

    // boot_snap is a Base snapshot — the root of all checkpoint chains loaded from disk.
    let boot_snap = Arc::new(NyxSnapshot {
        parent: None,
        depth: 0,
        memory: MemorySnapshot::Base(mem),
        state,
        tsc,
        continuation_state,
    });

    vm.active_snapshot = Some(boot_snap.clone());
    vm.apply_snapshot(&boot_snap);
    ensure_block_devices_activated(vm);
    Ok(Some((in_vaddr, out_vaddr, boot_snap, init_cow_arcs)))
}

/// Generate a random UUID v4.
fn new_uuid() -> CheckpointId {
    let path = std::path::Path::new("/proc/sys/kernel/random/uuid");
    if let Ok(s) = std::fs::read_to_string(path) {
        if let Ok(id) = parse_uuid(s.trim()) {
            return id;
        }
    }
    // Fallback: use timestamp + pid bytes (not cryptographically random but sufficient).
    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos() as u64;
    let pid = std::process::id() as u64;
    let mut id = [0u8; 16];
    id[..8].copy_from_slice(&ts.to_le_bytes());
    id[8..].copy_from_slice(&pid.to_le_bytes());
    id
}

/// Parse "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" into 16 bytes.
fn parse_uuid(s: &str) -> Result<CheckpointId> {
    let hex: String = s.chars().filter(|c| c.is_ascii_hexdigit()).collect();
    if hex.len() != 32 {
        bail!("invalid UUID: {s}");
    }
    let mut id = [0u8; 16];
    for i in 0..16 {
        id[i] = u8::from_str_radix(&hex[i * 2..i * 2 + 2], 16)
            .with_context(|| format!("bad UUID hex at {i}"))?;
    }
    Ok(id)
}

/// Format a CheckpointId as "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx".
fn format_uuid(id: &CheckpointId) -> String {
    format!(
        "{:02x}{:02x}{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}-{:02x}{:02x}{:02x}{:02x}{:02x}{:02x}",
        id[0], id[1], id[2], id[3],
        id[4], id[5],
        id[6], id[7],
        id[8], id[9],
        id[10], id[11], id[12], id[13], id[14], id[15]
    )
}

fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().collect();

    // Parse optional --snapshot-dir <path> and --single-shot before the required vmconfig arg.
    let mut snapshot_dir: Option<PathBuf> = None;
    let mut single_shot = false;
    let mut pos_args: Vec<&str> = Vec::new();
    let mut i = 1;
    while i < args.len() {
        if args[i] == "--snapshot-dir" {
            i += 1;
            if i >= args.len() {
                eprintln!("--snapshot-dir requires a path argument");
                std::process::exit(1);
            }
            snapshot_dir = Some(PathBuf::from(&args[i]));
        } else if args[i] == "--single-shot" {
            single_shot = true;
        } else {
            pos_args.push(&args[i]);
        }
        i += 1;
    }

    if pos_args.is_empty() {
        eprintln!(
            "Usage: opendst-nyx-shim [--snapshot-dir <path>] [--single-shot] <vmconfig.json>"
        );
        std::process::exit(1);
    }
    let config_json = fs::read_to_string(pos_args[0])
        .with_context(|| format!("reading vmconfig: {}", pos_args[0]))?;

    // Create the snapshot directory if requested.
    if let Some(ref dir) = snapshot_dir {
        fs::create_dir_all(dir)
            .with_context(|| format!("creating snapshot-dir {}", dir.display()))?;
    }

    let mut vm = NyxVM::new("opendst-nyx-shim".to_string(), &config_json);

    // Fast-path: if the boot snapshot is already on disk, skip the full guest boot.
    let (boot_snapshot, in_vaddr, out_vaddr, boot_cow_arcs) = if let Some(ref dir) = snapshot_dir {
        match load_and_apply_boot_from_disk(dir, &mut vm)? {
            Some((iv, ov, snap, cows)) => (snap, iv, ov, cows),
            None => {
                // First run: boot normally and save the result to disk.
                let (snap, iv, ov) = boot_to_snapshot(&mut vm)?;
                let cows = extract_cow_arcs(&snap);
                if let Err(e) = save_boot_to_disk(dir, iv, ov, &snap) {
                    eprintln!("[shim] warning: failed to save boot snapshot: {e}");
                }
                (snap, iv, ov, cows)
            }
        }
    } else {
        let (snap, iv, ov) = boot_to_snapshot(&mut vm)?;
        let cows = extract_cow_arcs(&snap);
        (snap, iv, ov, cows)
    };

    println!("ready");
    io::stdout().flush()?;

    let mut store = SnapshotStore::new_with_boot_cow(boot_snapshot, snapshot_dir, boot_cow_arcs);
    // Reload checkpoints saved by previous shim processes (per-iteration shim restart).
    store.load_disk_index();

    let stdin = io::stdin();
    let mut line = String::new();
    loop {
        line.clear();
        if stdin.lock().read_line(&mut line)? == 0 {
            break;
        }
        let plan_json = line.trim_end_matches('\n');
        if plan_json.is_empty() {
            continue;
        }

        // Parse checkpoint UUID from plan (null → nil UUID = initial snapshot).
        let checkpoint_id = parse_plan_checkpoint(plan_json).unwrap_or(nil_uuid());

        // Look up snapshot; fall back to initial if not found (e.g. taken by a different shim).
        let (restore_snap, restore_cursor, checkpoint_iteration) = match store.get(&checkpoint_id) {
            Some(entry) => entry,
            None => {
                if checkpoint_id != nil_uuid() {
                    eprintln!(
                        "[shim] checkpoint {} not in cache, replaying from scratch",
                        format_uuid(&checkpoint_id)
                    );
                }
                // Use nil UUID entry and reset checkpoint_iteration to 0 so resume_segment_idx
                // is recomputed from the start — the plan will be replayed in full.
                let (snap, cursor, _) = store.get(&nil_uuid()).unwrap();
                (snap, cursor, 0u64)
            }
        };

        // Parse all segments. Determine resume_segment_idx from the checkpoint.
        // If nextBoundary > 0 (new plans): the guest's frozen nextIteration equals the
        // `until` of the in-progress segment, so the first segment with until > nextBoundary
        // is already the *next* one to deliver — no +1 skip needed.
        // If nextBoundary == 0 (legacy): fall back to the old heuristic of finding the
        // in-progress segment by checkpoint_iteration and skipping it with +1.
        let all_segments = parse_plan_segments(plan_json);
        let next_boundary = parse_plan_checkpoint_next_boundary(plan_json);
        let resume_segment_idx = if next_boundary > 0 {
            all_segments
                .iter()
                .position(|&(_, until)| until > next_boundary)
                .unwrap_or(all_segments.len())
        } else {
            all_segments
                .iter()
                .position(|&(_, until)| until > checkpoint_iteration)
                .map(|i| i + 1)
                .unwrap_or(all_segments.len())
        };
        let tail_segments: Vec<(u64, u64)> = all_segments[resume_segment_idx..].to_vec();

        vm.apply_snapshot(&restore_snap);
        ensure_block_devices_activated(&mut vm);
        write_plan(&mut vm, in_vaddr, plan_json.as_bytes())?;
        partial_zero_output(&mut vm, out_vaddr, restore_cursor);

        let result = panic::catch_unwind(panic::AssertUnwindSafe(|| {
            run_iteration(
                &mut vm,
                out_vaddr,
                in_vaddr,
                &tail_segments,
                restore_cursor,
                checkpoint_id,
                &mut store,
            )
        }));
        match result {
            Ok(Ok(())) => {}
            Ok(Err(e)) => return Err(e),
            Err(_) => {
                eprintln!("[shim] run_iteration panicked — guest VM may have crashed");
                std::process::exit(2);
            }
        }
        println!("SHIM_DONE");
        io::stdout().flush()?;
        if single_shot {
            std::process::exit(0);
        }
    }

    std::process::exit(0);
}

/// Parses the checkpoint id string from plan JSON.
/// Returns None if "checkpoint" is null or absent.
fn parse_plan_checkpoint(plan_json: &str) -> Option<CheckpointId> {
    let key = "\"checkpoint\"";
    let start = plan_json.find(key)? + key.len();
    let rest = plan_json[start..].trim_start_matches([' ', ':']);
    if rest.starts_with("null") {
        return None;
    }
    // Expect {"id":"<uuid>"}
    let id_key = "\"id\"";
    let id_start = rest.find(id_key)? + id_key.len();
    let after_key = rest[id_start..].trim_start_matches([' ', ':']);
    if !after_key.starts_with('"') {
        return None;
    }
    let uuid_start = 1;
    let uuid_end = after_key[uuid_start..].find('"')? + uuid_start;
    parse_uuid(&after_key[uuid_start..uuid_end]).ok()
}

/// Parses the `nextBoundary` field from the plan's checkpoint object.
/// Returns 0 if absent (legacy plan without the field).
fn parse_plan_checkpoint_next_boundary(plan_json: &str) -> u64 {
    let key = "\"checkpoint\"";
    let start = match plan_json.find(key) {
        Some(s) => s + key.len(),
        None => return 0,
    };
    let rest = plan_json[start..].trim_start_matches([' ', ':']);
    if rest.starts_with("null") {
        return 0;
    }
    let nb_key = "\"nextBoundary\"";
    let nb_start = match rest.find(nb_key) {
        Some(s) => s + nb_key.len(),
        None => return 0,
    };
    let after = rest[nb_start..].trim_start_matches([' ', ':']);
    // Read digits
    let end = after
        .find(|c: char| !c.is_ascii_digit())
        .unwrap_or(after.len());
    after[..end].parse::<u64>().unwrap_or(0)
}

/// Parses all (seed, until) pairs from the plan's segments array.
fn parse_plan_segments(plan_json: &str) -> Vec<(u64, u64)> {
    let mut result = Vec::new();
    let Some(seg_start) = plan_json.find("\"segments\"") else {
        return result;
    };
    let after = &plan_json[seg_start..];
    let Some(arr_start) = after.find('[') else {
        return result;
    };
    let arr = &after[arr_start..];

    let mut pos = 1usize;
    while pos < arr.len() {
        let c = arr.as_bytes()[pos];
        if c == b']' {
            break;
        }
        if c != b'{' {
            pos += 1;
            continue;
        }
        let Some(obj_end) = arr[pos..].find('}') else {
            break;
        };
        let obj = &arr[pos..pos + obj_end + 1];
        if let (Some(seed), Some(until)) = (extract_u64(obj, "seed"), extract_u64(obj, "until")) {
            result.push((seed, until));
        }
        pos += obj_end + 1;
    }
    result
}

fn extract_u64(obj: &str, field: &str) -> Option<u64> {
    let key = format!("\"{}\":", field);
    let start = obj.find(&key)? + key.len();
    let rest = obj[start..].trim_start_matches(' ');
    let (rest, negative) = if rest.starts_with('-') {
        (&rest[1..], true)
    } else {
        (rest, false)
    };
    let end = rest
        .find(|c: char| !c.is_ascii_digit())
        .unwrap_or(rest.len());
    let n: i64 = rest[..end].parse().ok()?;
    Some(if negative { (-n) as u64 } else { n as u64 })
}

fn run_iteration(
    vm: &mut NyxVM,
    out_vaddr: u64,
    in_vaddr: u64,
    plan_segments: &[(u64, u64)],
    restore_cursor: usize,
    parent_checkpoint_id: CheckpointId,
    store: &mut SnapshotStore,
) -> Result<()> {
    let mut cursor: usize = restore_cursor;
    let mut segments_delivered: usize = 0;
    // Track the parent id for the next snapshot taken in this iteration.
    // If multiple snapshots are taken, each becomes the parent of the next.
    let mut current_parent_id = parent_checkpoint_id;

    loop {
        match vm.run(Duration::from_millis(ITER_TIMEOUT_MS)) {
            ExitReason::ExecDone(_) => {
                flush_output(vm, out_vaddr, &mut cursor);
                return Ok(());
            }
            ExitReason::RequestSnapshot => {
                // Periodic (or pre-boundary) snapshot issued from Scheduler context.
                // Take the snapshot and emit a checkpoint line — do NOT write a segment
                // to INPUT here; segment delivery is handled by SEGMENT_BOUNDARY_HC.
                flush_output(vm, out_vaddr, &mut cursor);

                let snap_iteration = extract_current_iteration(vm, out_vaddr, cursor);
                let snap = vm.take_snapshot();
                let uuid = new_uuid();
                let hash = extract_current_hash(vm, out_vaddr, cursor);
                // nextBoundary is the guest's frozen nextIteration — the `until` of the
                // segment currently in progress.  It equals the last delivered segment's
                // `until` value.  When 0 (no segment delivered yet) the runner falls back
                // to the legacy checkpoint_iteration heuristic.
                let next_boundary: u64 = if segments_delivered > 0 {
                    plan_segments[segments_delivered - 1].1
                } else {
                    0
                };
                store.insert(uuid, snap, cursor, snap_iteration, current_parent_id);
                current_parent_id = uuid;
                println!(
                    "{{\"source\":\"simulator\",\"type\":\"checkpoint\",\"id\":\"{}\",\"iteration\":{},\"hash\":{},\"nextBoundary\":{}}}",
                    format_uuid(&uuid),
                    snap_iteration,
                    hash.unwrap_or(0) as i32,
                    next_boundary,
                );
                io::stdout().flush()?;
                // Resume without writing anything to INPUT.
            }
            ExitReason::Hypercall(SEGMENT_BOUNDARY_HC, _, _, _, _) => {
                // Segment boundary reached in Scheduler context. Write the next segment
                // (or end-of-plan sentinel) to INPUT so the guest's NYX_SEGMENT_SUPPLIER
                // can read it.
                flush_output(vm, out_vaddr, &mut cursor);
                if segments_delivered < plan_segments.len() {
                    let (seed, until) = plan_segments[segments_delivered];
                    write_next_segment(vm, in_vaddr, seed, until);
                } else {
                    write_end_of_plan(vm, in_vaddr);
                }
                segments_delivered += 1;
                // Resume without taking a snapshot.
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
                eprintln!(
                    "[shim] VM shutdown (guest powered off without EXECDONE) cursor={cursor}"
                );
                // Peek at the start of the output buffer (one page, always present)
                // to capture any jvm-exit / jvm-exception diagnostic written by the
                // guest before it exited.  We deliberately do NOT read the full
                // OUTPUT_BUF_SIZE here to avoid the nyx_vm assert on absent pages.
                let safe_len = (OUTPUT_BUF_SIZE - cursor).min(4096);
                if safe_len > 0 {
                    let peek = vm.read_current_bytes(out_vaddr + cursor as u64, safe_len);
                    // Print as hex + ascii dump so binary content is also visible.
                    let printable: String = peek
                        .iter()
                        .take(256)
                        .map(|&b| {
                            if b >= 0x20 && b < 0x7f {
                                b as char
                            } else {
                                '.'
                            }
                        })
                        .collect();
                    let hex: String = peek
                        .iter()
                        .take(32)
                        .map(|b| format!("{b:02x}"))
                        .collect::<Vec<_>>()
                        .join(" ");
                    eprintln!(
                        "[shim] shutdown-output: {} bytes readable, hex[0..32]={hex}",
                        peek.len()
                    );
                    // Print any complete text lines found.
                    let text = String::from_utf8_lossy(&peek);
                    for line in text
                        .split('\n')
                        .filter(|l| !l.trim_matches('\0').is_empty())
                    {
                        if line
                            .chars()
                            .all(|c| c.is_ascii() && (c >= ' ' || c == '\t'))
                        {
                            eprintln!("[shim] shutdown-output: {line}");
                        } else {
                            eprintln!("[shim] shutdown-output(ascii): {printable}");
                            break;
                        }
                    }
                }
                // Do NOT call flush_output here: the VM shut down from kernel context
                // (e.g. reboot() syscall after JVM native crash), so the active CR3 is
                // the kernel page table.  User-space addresses (the output buffer) are
                // not mapped in it, and read_current_bytes would return 0 bytes causing
                // the assert_eq!(bytes_copied, num_bytes) panic in nyx_vm.rs:679.
                // The peek above already captured any available diagnostic bytes.
                return Ok(());
            }
            other => {
                eprintln!("[shim] unexpected VM exit: {other:?} cursor={cursor}");
                flush_output(vm, out_vaddr, &mut cursor);
            }
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
                    if !s.ends_with('\n') {
                        println!();
                    }
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

/// Reads the last non-null line from the output buffer up to `cursor` and extracts
/// the "it" (iteration) field. Falls back to the "hash" field name if "it" absent.
fn extract_current_iteration(vm: &NyxVM, out_vaddr: u64, cursor: usize) -> u64 {
    let buf = vm.read_current_bytes(out_vaddr, OUTPUT_BUF_SIZE);
    let text = String::from_utf8_lossy(&buf[..cursor]);
    for line in text.lines().rev() {
        if let Some(it) = extract_u64(line, "it") {
            return it;
        }
    }
    0
}

/// Extracts the hash from the most recent log line up to `cursor`.
fn extract_current_hash(vm: &NyxVM, out_vaddr: u64, cursor: usize) -> Option<u32> {
    let buf = vm.read_current_bytes(out_vaddr, OUTPUT_BUF_SIZE);
    let text = String::from_utf8_lossy(&buf[..cursor]);
    for line in text.lines().rev() {
        if let Some(h) = extract_u64(line, "hash") {
            if h != 0 {
                return Some(h as u32);
            }
        }
    }
    None
}

fn write_next_segment(vm: &mut NyxVM, in_vaddr: u64, seed: u64, until: u64) {
    let cr3 = vm.sregs().cr3;
    let vmm = vm.vmm.lock().unwrap();
    vmm.write_virtual_bytes(cr3, in_vaddr, &seed.to_le_bytes())
        .unwrap();
    vmm.write_virtual_bytes(cr3, in_vaddr + 8, &until.to_le_bytes())
        .unwrap();
}

/// Writes zero sentinel to INPUT to signal end-of-plan.
fn write_end_of_plan(vm: &mut NyxVM, in_vaddr: u64) {
    let cr3 = vm.sregs().cr3;
    let vmm = vm.vmm.lock().unwrap();
    vmm.write_virtual_bytes(cr3, in_vaddr, &[0u8; 16]).unwrap();
}

fn boot_to_snapshot(vm: &mut NyxVM) -> Result<(Arc<NyxSnapshot>, u64, u64)> {
    let mut in_vaddr: Option<u64> = None;
    let mut out_vaddr: Option<u64> = None;

    loop {
        match vm.run(Duration::from_millis(BOOT_TIMEOUT_MS)) {
            ExitReason::SharedMem(name, vaddr, _size) => match name.trim_end_matches('\0') {
                "opendst-in" => {
                    in_vaddr = Some(vaddr);
                }
                "opendst-out" => {
                    out_vaddr = Some(vaddr);
                }
                other => eprintln!("[shim] unknown shared region: {other}"),
            },
            ExitReason::RequestSnapshot => {
                let snap = vm.take_snapshot();
                let in_addr = in_vaddr.context("guest never registered 'opendst-in'")?;
                let out_addr = out_vaddr.context("guest never registered 'opendst-out'")?;
                return Ok((snap, in_addr, out_addr));
            }
            ExitReason::DebugPrint(msg) => eprintln!("[guest] {msg}"),
            ExitReason::Hypercall(FAILTEST, ptr, _, _, _) => {
                let raw = vm.read_cstr_current(ptr);
                bail!(
                    "guest failure during boot: {}",
                    String::from_utf8_lossy(&raw)
                );
            }
            ExitReason::Timeout => bail!("VM timed out during boot"),
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
    vmm.write_virtual_bytes(cr3, in_vaddr, &len.to_le_bytes())
        .unwrap();
    vmm.write_virtual_bytes(cr3, in_vaddr + 4, plan_bytes)
        .unwrap();
    Ok(())
}

/// Zeroes the output buffer from `from_offset` to the end.
fn partial_zero_output(vm: &mut NyxVM, out_vaddr: u64, from_offset: usize) {
    if from_offset >= OUTPUT_BUF_SIZE {
        return;
    }
    let len = OUTPUT_BUF_SIZE - from_offset;
    let cr3 = vm.sregs().cr3;
    let vmm = vm.vmm.lock().unwrap();
    vmm.write_virtual_bytes(cr3, out_vaddr + from_offset as u64, &vec![0u8; len])
        .unwrap();
}
