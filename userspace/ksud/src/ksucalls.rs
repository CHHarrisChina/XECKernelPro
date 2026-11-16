#![allow(clippy::unreadable_literal)]
use anyhow::{Result, bail};

use crate::ksu_uapi;
use std::cell::Cell;
use std::fs;
use std::io;
use std::os::fd::{AsRawFd, FromRawFd, IntoRawFd, OwnedFd, RawFd};
use std::os::unix::fs::OpenOptionsExt;
use std::sync::Mutex;

// sigsys handler
std::thread_local! {
    #[allow(clippy::missing_const_for_thread_local)]
    static SVC_IN_FLIGHT: Cell<bool> = const { Cell::new(false) };
    #[allow(clippy::missing_const_for_thread_local)]
    static SIGSYS_OCCURRED: Cell<bool> = const { Cell::new(false) };
}

const SYS_SECCOMP: libc::c_int = 1;

fn with_svc_call<F, R>(call: F) -> R
where
    F: FnOnce() -> R,
{
    SVC_IN_FLIGHT.with(|in_flight| in_flight.set(true));
    let result = call();
    SVC_IN_FLIGHT.with(|in_flight| in_flight.set(false));
    result
}

fn take_sigsys_occurred() -> bool {
    SIGSYS_OCCURRED.with(|occurred| occurred.replace(false))
}

extern "C" fn sigsys_handler(
    _sig: libc::c_int,
    info: *mut libc::siginfo_t,
    ctx: *mut libc::c_void,
) {
    unsafe {
        if info.is_null() || ctx.is_null() || (*info).si_code != SYS_SECCOMP {
            return;
        }
        if SVC_IN_FLIGHT.with(Cell::get) {
            SIGSYS_OCCURRED.with(|occurred| occurred.set(true));
        }

        #[cfg(not(target_arch = "riscv64"))]
        let ucontext = ctx.cast::<libc::ucontext_t>();
        #[cfg(target_arch = "aarch64")]
        {
            (*ucontext).uc_mcontext.regs[0] = (-libc::EPERM) as u64;
        }
        #[cfg(target_arch = "x86_64")]
        {
            let rax = libc::REG_RAX as usize;
            (*ucontext).uc_mcontext.gregs[rax] = i64::from(-libc::EPERM);
        }
        #[cfg(target_arch = "riscv64")]
        {
            let ucontext = ctx.cast::<ksu_uapi::ucontext_t>();
            (*ucontext).uc_mcontext.__gregs[ksu_uapi::REG_A0 as usize] =
                (-libc::EPERM) as libc::c_ulong;
        }
    }
}

pub fn setup_sigsys_handler() {
    unsafe {
        let mut sa: libc::sigaction = std::mem::zeroed();
        sa.sa_flags = libc::SA_SIGINFO;
        sa.sa_sigaction = sigsys_handler as *const () as usize;
        libc::sigemptyset(std::ptr::addr_of_mut!(sa.sa_mask));
        if libc::sigaction(libc::SIGSYS, std::ptr::addr_of!(sa), std::ptr::null_mut()) != 0 {
            let error = std::io::Error::last_os_error();
            log::warn!("Failed to set SIGSYS handler: {error}");
        }
    }
}

// 这两个名字来自内核的 anon_inode_getfile()（见 kernel/supercall/supercall.c），
// 出现在 /proc/self/fd 的 readlink 结果里，必须与内核侧逐字一致，否则找不到驱动 fd。
const DRIVER_FD_NAME: &str = "anon_inode:[xecpro_driver]";
const SU_DRIVER_FD_NAME: &str = "anon_inode:[xecpro_driver_su]";

// Serialize acquisition, validation and ioctl use so cache replacement cannot
// close a descriptor while another caller is using it.
static DRIVER_FD: Mutex<Option<OwnedFd>> = Mutex::new(None);

fn is_own_driver_fd(fd: RawFd) -> bool {
    fs::read_link(format!("/proc/self/fd/{fd}")).is_ok_and(|target| {
        target == std::path::Path::new(DRIVER_FD_NAME)
            || target == std::path::Path::new(SU_DRIVER_FD_NAME)
    })
}

fn validate_driver_cache(cache: &mut Option<OwnedFd>) {
    if cache
        .as_ref()
        .is_some_and(|fd| !is_own_driver_fd(fd.as_raw_fd()))
    {
        // A raw close elsewhere (for example, unload) may already have freed
        // this number. Do not close it again: it may now name an unrelated fd.
        if let Some(stale) = cache.take() {
            let _ = stale.into_raw_fd();
        }
    }
}

fn ensure_driver_fd(cache: &mut Option<OwnedFd>) -> bool {
    validate_driver_cache(cache);
    if cache.is_none() {
        *cache = init_driver_fd();
    }
    cache.is_some()
}

/// Recognize this implementation, rather than any driver sharing KSU ioctls.
/// Keep a failed probe retryable: late-load may install our module afterwards.
pub fn probe_own_driver() -> bool {
    let mut cache = DRIVER_FD
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    ensure_driver_fd(&mut cache)
}

fn scan_driver_fd() -> io::Result<Option<OwnedFd>> {
    // Finish enumeration before creating duplicates, so the scan cannot visit
    // its own newly allocated descriptors.
    let candidates: Vec<RawFd> = fs::read_dir("/proc/self/fd")?
        .flatten()
        .filter_map(|entry| entry.file_name().to_string_lossy().parse().ok())
        .collect();
    let mut driver_fd = None;

    for fd_num in candidates {
        if !is_own_driver_fd(fd_num) {
            continue;
        }
        // Borrowed inherited fds can be closed by their owner. Duplicate first,
        // then validate the duplicate to reject a close/reuse during the scan.
        let duplicate = unsafe { libc::fcntl(fd_num, libc::F_DUPFD_CLOEXEC, 0) };
        if duplicate < 0 {
            continue;
        }
        // SAFETY: fcntl returned a new descriptor owned by this acquisition.
        let fd = unsafe { OwnedFd::from_raw_fd(duplicate) };
        let Ok(target) = fs::read_link(format!("/proc/self/fd/{}", fd.as_raw_fd())) else {
            continue;
        };
        if target == std::path::Path::new(SU_DRIVER_FD_NAME) {
            return Ok(Some(fd));
        }
        if target == std::path::Path::new(DRIVER_FD_NAME) {
            driver_fd = Some(fd);
        }
    }

    Ok(driver_fd)
}

pub fn claim_inherited_driver_fd() -> io::Result<()> {
    let mut cache = DRIVER_FD
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    validate_driver_cache(&mut cache);
    if cache.is_none() {
        *cache = scan_driver_fd()?;
    }
    drop(cache);
    Ok(())
}

fn init_driver_fd() -> Option<OwnedFd> {
    if let Some(fd) = scan_driver_fd().ok().flatten() {
        return Some(fd);
    }
    let mut fd = -1;
    with_svc_call(|| unsafe {
        libc::syscall(
            libc::SYS_reboot,
            ksu_uapi::KSU_INSTALL_MAGIC1,
            ksu_uapi::KSU_INSTALL_MAGIC2,
            0,
            &mut fd,
        )
    });
    if take_sigsys_occurred() {
        eprintln!("KernelSU driver install syscall was blocked by seccomp");
        log::error!("KernelSU driver install syscall was blocked by seccomp");
    }
    if fd < 0 {
        return None;
    }
    // SAFETY: the install syscall returned a new descriptor to this caller.
    let fd = unsafe { OwnedFd::from_raw_fd(fd) };
    is_own_driver_fd(fd.as_raw_fd()).then_some(fd)
}

// ioctl wrapper using libc
pub fn ksuctl<T>(request: u32, arg: *mut T) -> Result<i32> {
    let mut cache = DRIVER_FD
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if !ensure_driver_fd(&mut cache) {
        bail!("could not retrieve own XEC driver fd")
    }
    let fd = cache
        .as_ref()
        .expect("own driver acquisition populated cache");
    let ret = unsafe { libc::ioctl(fd.as_raw_fd(), request as i32, arg) };
    if ret < 0 {
        let error = io::Error::last_os_error();
        validate_driver_cache(&mut cache);
        // Never replay an ioctl: a mutating request may already have taken
        // effect. A later call can reacquire a stale or missing descriptor.
        bail!("ksuctl failed: {error}")
    }
    drop(cache);
    Ok(ret)
}

// API implementations
pub fn get_info() -> ksu_uapi::ksu_get_info_cmd {
    // GET_INFO also schedules the kernel's existing signature-based recovery.
    // A cached failure or compatibility answer must not suppress later retries.
    let mut cmd = ksu_uapi::ksu_get_info_cmd {
        version: 0,
        flags: 0,
        features: 0,
        uapi_version: 0,
    };
    if ksuctl(ksu_uapi::KSU_IOCTL_GET_INFO, &raw mut cmd).is_err() {
        cmd = ksu_uapi::ksu_get_info_cmd {
            version: 0,
            flags: 0,
            features: 0,
            uapi_version: 0,
        };
        let _ = ksuctl(ksu_uapi::KSU_IOCTL_GET_INFO_LEGACY, &raw mut cmd);
    }
    cmd
}

pub fn get_version() -> i32 {
    get_info().version as i32
}

pub fn is_late_load() -> bool {
    get_info().flags & ksu_uapi::KSU_GET_INFO_FLAG_LATE_LOAD != 0
}

pub fn is_lkm() -> bool {
    get_info().flags & ksu_uapi::KSU_GET_INFO_FLAG_LKM != 0
}

/// Whether the kernel runs module preinit actions on boot.
pub fn is_preinit_supported() -> bool {
    get_info().flags & ksu_uapi::KSU_GET_INFO_FLAG_PREINIT != 0
}

pub const fn uapi_version() -> u32 {
    ksu_uapi::KERNEL_SU_UAPI_VERSION
}

pub fn runtime_mode() -> &'static str {
    if is_late_load() {
        "late-load"
    } else if is_lkm() {
        "lkm"
    } else {
        "built-in"
    }
}

/// Root boot stages can recover identity without an app-side reboot syscall.
pub fn recover_manager_identity() -> anyhow::Result<bool> {
    if unsafe { libc::geteuid() } != 0 {
        bail!("manager recovery requires the existing root boot process");
    }
    let mut before: u32 = u32::MAX;
    ksuctl(ksu_uapi::KSU_IOCTL_GET_MANAGER_APPID, &raw mut before)?;
    let mut info = ksu_uapi::ksu_get_info_cmd {
        version: 0,
        flags: 0,
        features: 0,
        uapi_version: 0,
    };
    // Existing GET_INFO schedules signature-verified task_work. It completes
    // on this thread's syscall return; no new kernel ABI or UID override.
    ksuctl(ksu_uapi::KSU_IOCTL_GET_INFO, &raw mut info)?;
    let mut after: u32 = u32::MAX;
    ksuctl(ksu_uapi::KSU_IOCTL_GET_MANAGER_APPID, &raw mut after)?;
    Ok(before == u32::MAX && after != u32::MAX && after != 0)
}

pub fn recover_manager_at_boot() {
    if unsafe { libc::geteuid() } != 0 {
        return;
    }
    match recover_manager_identity() {
        Ok(true) => {
            // Defer ActivityManager interaction until boot-completed.
            let boot_id = fs::read_to_string("/proc/sys/kernel/random/boot_id");
            let result = boot_id.and_then(|id| {
                use std::io::Write;
                let marker = "/data/adb/ksu/.manager-recovery-pending";
                fs::create_dir_all("/data/adb/ksu")?;
                if let Ok(previous) = fs::read_to_string(marker) {
                    if previous == id {
                        return Ok(());
                    }
                    fs::remove_file(marker)?;
                }
                let mut file = fs::OpenOptions::new()
                    .write(true)
                    .create_new(true)
                    .mode(0o600)
                    .open(marker)?;
                file.write_all(id.as_bytes())
            });
            if let Err(error) = result {
                log::warn!("cannot record manager recovery: {error}");
            }
        }
        Ok(false) => {}
        Err(error) => log::warn!("own-driver manager recovery unavailable: {error:#}"),
    }
}

pub fn finish_manager_recovery() {
    if unsafe { libc::geteuid() } != 0 {
        return;
    }
    let marker = "/data/adb/ksu/.manager-recovery-pending";
    let pending = fs::read_to_string(marker).ok();
    let boot_id = fs::read_to_string("/proc/sys/kernel/random/boot_id").ok();
    if pending.is_some() && pending == boot_id {
        // Only called after Android boot completion. The bundled timeout bounds
        // ActivityManager waiting; retain the marker if the command fails.
        match std::process::Command::new(crate::assets::busybox_path())
            .args([
                "timeout",
                "-s",
                "KILL",
                "10",
                "/system/bin/am",
                "force-stop",
                "--user",
                "all",
                crate::defs::DEFAULT_PACKAGE_NAME,
            ])
            .status()
        {
            Ok(status) if status.success() => {
                let _ = fs::remove_file(marker);
            }
            Ok(status) => log::warn!("manager force-stop failed: {status}"),
            Err(error) => log::warn!("manager force-stop unavailable: {error}"),
        }
    } else if pending.is_some() {
        let _ = fs::remove_file(marker);
    }
}

pub fn ensure_uapi_version_matched() -> anyhow::Result<()> {
    let kernel_uapi = get_info().uapi_version;
    let userspace_uapi = uapi_version();
    if kernel_uapi != userspace_uapi {
        bail!(
            "UAPI version mismatch: kernel={kernel_uapi}, ksud={userspace_uapi}. Please update KernelSU!"
        );
    }
    Ok(())
}

pub fn grant_root() -> Result<()> {
    ksuctl(ksu_uapi::KSU_IOCTL_GRANT_ROOT, std::ptr::null_mut::<u8>())?;
    Ok(())
}

fn report_event(event: u32) {
    let mut cmd = ksu_uapi::ksu_report_event_cmd { event };
    let _ = ksuctl(ksu_uapi::KSU_IOCTL_REPORT_EVENT, &raw mut cmd);
}

pub fn report_post_fs_data() {
    report_event(ksu_uapi::EVENT_POST_FS_DATA);
}

pub fn report_services() -> Result<bool> {
    let mut cmd = ksu_uapi::ksu_report_event_cmd {
        event: ksu_uapi::EVENT_SERVICES,
    };
    Ok(ksuctl(ksu_uapi::KSU_IOCTL_REPORT_EVENT, &raw mut cmd)? == 1)
}

pub fn report_boot_complete() {
    report_event(ksu_uapi::EVENT_BOOT_COMPLETED);
}

pub fn report_module_mounted() {
    report_event(ksu_uapi::EVENT_MODULE_MOUNTED);
}

pub fn check_kernel_safemode() -> bool {
    let mut cmd = ksu_uapi::ksu_check_safemode_cmd { in_safe_mode: 0 };
    let _ = ksuctl(ksu_uapi::KSU_IOCTL_CHECK_SAFEMODE, &raw mut cmd);
    cmd.in_safe_mode != 0
}

pub fn set_sepolicy(payload: *const u8, payload_len: u64) -> Result<i32> {
    let mut ioctl_cmd = crate::ksu_uapi::ksu_set_sepolicy_cmd {
        data_len: payload_len,
        data: payload as u64,
    };

    ksuctl(ksu_uapi::KSU_IOCTL_SET_SEPOLICY, &raw mut ioctl_cmd)
}

#[derive(Debug, Clone, Copy, Default)]
pub struct FeatureState {
    pub value: u64,
    pub supported: bool,
    /// Forced by boot config (ksu_config), the kernel refuses to change it
    pub forced: bool,
}

/// Get feature value and support status from kernel
pub fn get_feature(feature_id: u32) -> Result<FeatureState> {
    let mut cmd = ksu_uapi::ksu_get_feature_cmd {
        feature_id,
        value: 0,
        supported: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_GET_FEATURE, &raw mut cmd)?;
    let flags = u32::from(cmd.supported);
    Ok(FeatureState {
        value: cmd.value,
        // older kernels report 1 for supported, matching the flag
        supported: flags & ksu_uapi::KSU_FEATURE_FLAG_SUPPORTED != 0,
        forced: flags & ksu_uapi::KSU_FEATURE_FLAG_FORCED != 0,
    })
}

/// Set feature value in kernel
pub fn set_feature(feature_id: u32, value: u64) -> Result<()> {
    let mut cmd = ksu_uapi::ksu_set_feature_cmd { feature_id, value };
    ksuctl(ksu_uapi::KSU_IOCTL_SET_FEATURE, &raw mut cmd)?;
    Ok(())
}

pub fn get_wrapped_fd(fd: RawFd) -> Result<RawFd> {
    let mut cmd = ksu_uapi::ksu_get_wrapper_fd_cmd {
        fd: fd as u32,
        flags: 0,
    };
    let result = ksuctl(ksu_uapi::KSU_IOCTL_GET_WRAPPER_FD, &raw mut cmd)?;
    Ok(result)
}

pub fn get_sulog_fd() -> Result<RawFd> {
    let mut cmd = ksu_uapi::ksu_get_sulog_fd_cmd { flags: 0 };
    let result = ksuctl(ksu_uapi::KSU_IOCTL_GET_SULOG_FD, &raw mut cmd)?;
    Ok(result)
}

/// Get mark status for a process (pid=0 returns total marked count)
pub fn mark_get(pid: i32) -> Result<u32> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_GET,
        pid,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(cmd.result)
}

/// Mark a process (pid=0 marks all processes)
pub fn mark_set(pid: i32) -> Result<()> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_MARK,
        pid,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(())
}

/// Unmark a process (pid=0 unmarks all processes)
pub fn mark_unset(pid: i32) -> Result<()> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_UNMARK,
        pid,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(())
}

/// Refresh mark for all running processes
pub fn mark_refresh() -> Result<()> {
    let mut cmd = ksu_uapi::ksu_manage_mark_cmd {
        operation: ksu_uapi::KSU_MARK_REFRESH,
        pid: 0,
        result: 0,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_MANAGE_MARK, &raw mut cmd)?;
    Ok(())
}

pub fn nuke_ext4_sysfs(mnt: &str) -> anyhow::Result<()> {
    let c_mnt = std::ffi::CString::new(mnt)?;
    let mut ioctl_cmd = ksu_uapi::ksu_nuke_ext4_sysfs_cmd {
        arg: c_mnt.as_ptr() as u64,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_NUKE_EXT4_SYSFS, &raw mut ioctl_cmd)?;
    Ok(())
}

/// Wipe all entries from umount list
pub fn umount_list_wipe() -> Result<()> {
    let mut cmd = ksu_uapi::ksu_add_try_umount_cmd {
        arg: 0,
        flags: 0,
        mode: ksu_uapi::KSU_UMOUNT_WIPE,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_ADD_TRY_UMOUNT, &raw mut cmd)?;
    Ok(())
}

/// Add mount point to umount list
pub fn umount_list_add(path: &str, flags: u32) -> anyhow::Result<()> {
    let c_path = std::ffi::CString::new(path)?;
    let mut cmd = ksu_uapi::ksu_add_try_umount_cmd {
        arg: c_path.as_ptr() as u64,
        flags,
        mode: ksu_uapi::KSU_UMOUNT_ADD,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_ADD_TRY_UMOUNT, &raw mut cmd)?;
    Ok(())
}

/// Delete mount point from umount list
pub fn umount_list_del(path: &str) -> anyhow::Result<()> {
    let c_path = std::ffi::CString::new(path)?;
    let mut cmd = ksu_uapi::ksu_add_try_umount_cmd {
        arg: c_path.as_ptr() as u64,
        flags: 0,
        mode: ksu_uapi::KSU_UMOUNT_DEL,
    };
    ksuctl(ksu_uapi::KSU_IOCTL_ADD_TRY_UMOUNT, &raw mut cmd)?;
    Ok(())
}

/// Set current process's process group to init_group (pgid = 0)
pub fn set_init_pgrp() -> Result<()> {
    ksuctl(
        ksu_uapi::KSU_IOCTL_SET_INIT_PGRP,
        std::ptr::null_mut::<u8>(),
    )?;
    Ok(())
}

/// Look up the addresses of kernel symbols, exported or not.
///
/// Returns one entry per name, `None` when the running kernel has no such
/// symbol.
pub fn lookup_symbols(names: &[&str]) -> Result<Vec<Option<u64>>> {
    let mut result = Vec::with_capacity(names.len());
    for chunk in names.chunks(ksu_uapi::KSU_LOOKUP_SYMBOLS_MAX as usize) {
        let c_names = chunk
            .iter()
            .map(|name| std::ffi::CString::new(*name))
            .collect::<Result<Vec<_>, _>>()?;
        let ptrs: Vec<u64> = c_names.iter().map(|name| name.as_ptr() as u64).collect();
        let mut addrs = vec![0u64; chunk.len()];
        let mut cmd = ksu_uapi::ksu_lookup_symbols_cmd {
            names: ptrs.as_ptr() as u64,
            addrs: addrs.as_mut_ptr() as u64,
            count: chunk.len() as u32,
            flags: 0,
        };
        ksuctl(ksu_uapi::KSU_IOCTL_LOOKUP_SYMBOLS, &raw mut cmd)?;
        result.extend(addrs.into_iter().map(|addr| (addr != 0).then_some(addr)));
    }
    Ok(result)
}

pub fn set_ksu_no_new_privs() -> anyhow::Result<()> {
    let result = ksuctl(
        ksu_uapi::KSU_IOCTL_DISABLE_ESCAPE_TO_ROOT,
        std::ptr::null_mut::<u8>(),
    )?;
    if result != 0 {
        bail!("unexpected result: {result}");
    }
    Ok(())
}
