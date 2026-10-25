//! Android file locks using libc; Rust std File locking is unsupported on this target.
use std::fs::{File, TryLockError};
use std::io;

#[cfg(any(target_os = "android", test))]
fn native_lock(file: &File, operation: i32) -> io::Result<()> {
    use std::os::fd::AsRawFd;
    unsafe extern "C" { fn flock(fd: i32, operation: i32) -> i32; }
    loop {
        // The descriptor is borrowed for this call; flock does not take ownership.
        if unsafe { flock(file.as_raw_fd(), operation) } == 0 { return Ok(()); }
        let error = io::Error::last_os_error();
        if error.kind() != io::ErrorKind::Interrupted { return Err(error); }
    }
}

pub fn lock(file: &File) -> io::Result<()> {
    #[cfg(any(target_os = "android", test))]
    { native_lock(file, 2) }
    #[cfg(not(any(target_os = "android", test)))]
    { file.lock() }
}

pub fn try_lock(file: &File) -> Result<(), TryLockError> {
    #[cfg(any(target_os = "android", test))]
    { native_lock(file, 2 | 4).map_err(|error| {
        if error.kind() == io::ErrorKind::WouldBlock { TryLockError::WouldBlock }
        else { TryLockError::Error(error) }
    }) }
    #[cfg(not(any(target_os = "android", test)))]
    { file.try_lock() }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs::OpenOptions;
    #[test]
    fn independent_descriptors_contend_and_close_releases() {
        let path = std::env::temp_dir().join(format!("aster-lock-{}", std::process::id()));
        let first = OpenOptions::new().read(true).write(true).create_new(true).open(&path).unwrap();
        let second = OpenOptions::new().read(true).write(true).open(&path).unwrap();
        lock(&first).unwrap();
        assert!(matches!(try_lock(&second), Err(TryLockError::WouldBlock)));
        drop(first);
        try_lock_shared(&second).unwrap();
        let third = OpenOptions::new().read(true).write(true).open(&path).unwrap();
        try_lock_shared(&third).unwrap();
        assert!(matches!(try_lock(&second), Err(TryLockError::WouldBlock)));
        unlock(&third).unwrap();
        try_lock(&second).unwrap();
        unlock(&second).unwrap();
        try_lock(&third).unwrap();
        drop(third);
        drop(second);
        std::fs::remove_file(path).unwrap();
    }
}

pub fn try_lock_shared(file: &File) -> Result<(), TryLockError> {
    #[cfg(any(target_os = "android", test))]
    { native_lock(file, 1 | 4).map_err(|error| if error.kind() == io::ErrorKind::WouldBlock { TryLockError::WouldBlock } else { TryLockError::Error(error) }) }
    #[cfg(not(any(target_os = "android", test)))]
    { file.try_lock_shared() }
}
pub fn unlock(file: &File) -> io::Result<()> {
    #[cfg(any(target_os = "android", test))]
    { native_lock(file, 8) }
    #[cfg(not(any(target_os = "android", test)))]
    { file.unlock() }
}
