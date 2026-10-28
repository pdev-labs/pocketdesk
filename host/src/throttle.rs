//! Per-address throttling for wrong pairing codes.
//!
//! An 8-character code is ~40 bits, which is plenty for a human to type but
//! would be brute-forceable if a host accepted unlimited guesses. Six free
//! attempts per address, then a lock that starts at one minute and doubles up
//! to fifteen, makes online guessing pointless without annoying a typo.

use std::{
    collections::HashMap,
    net::IpAddr,
    sync::Mutex,
    time::{Duration, Instant},
};

const FREE_ATTEMPTS: u32 = 6;
const BASE_LOCK: Duration = Duration::from_secs(60);
const MAX_LOCK: Duration = Duration::from_secs(900);
/// Forget an address after an hour of quiet, so the map cannot grow forever.
const FORGET_AFTER: Duration = Duration::from_secs(3600);

#[derive(Clone, Copy)]
struct Entry {
    failures: u32,
    locked_until: Option<Instant>,
    last_seen: Instant,
}

pub struct Throttle {
    entries: Mutex<HashMap<IpAddr, Entry>>,
}

impl Default for Throttle {
    fn default() -> Self {
        Self::new()
    }
}

impl Throttle {
    pub fn new() -> Self {
        Self {
            entries: Mutex::new(HashMap::new()),
        }
    }

    /// `Err(remaining)` when this address is locked out.
    pub fn check(&self, ip: IpAddr) -> Result<(), Duration> {
        let mut entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        entries.retain(|_, entry| entry.last_seen.elapsed() < FORGET_AFTER);

        if let Some(entry) = entries.get(&ip) {
            if let Some(until) = entry.locked_until {
                let now = Instant::now();
                if until > now {
                    return Err(until - now);
                }
            }
        }
        Ok(())
    }

    pub fn fail(&self, ip: IpAddr) {
        let now = Instant::now();
        let mut entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        let entry = entries.entry(ip).or_insert(Entry {
            failures: 0,
            locked_until: None,
            last_seen: now,
        });

        entry.failures = entry.failures.saturating_add(1);
        entry.last_seen = now;

        if entry.failures >= FREE_ATTEMPTS {
            // 1, 2, 4, 8, 15, 15… minutes.
            let doublings = (entry.failures - FREE_ATTEMPTS).min(4);
            let lock = BASE_LOCK
                .saturating_mul(1u32 << doublings)
                .min(MAX_LOCK);
            entry.locked_until = Some(now + lock);
        }
    }

    pub fn success(&self, ip: IpAddr) {
        let mut entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        entries.remove(&ip);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ip(last: u8) -> IpAddr {
        IpAddr::from([192, 168, 1, last])
    }

    #[test]
    fn allows_typos_then_locks() {
        let throttle = Throttle::new();
        for _ in 0..FREE_ATTEMPTS - 1 {
            throttle.fail(ip(10));
        }
        assert!(throttle.check(ip(10)).is_ok(), "typos should not lock yet");

        throttle.fail(ip(10));
        let wait = throttle.check(ip(10)).expect_err("should be locked");
        assert!(wait > Duration::from_secs(30) && wait <= BASE_LOCK);
    }

    #[test]
    fn locks_only_the_offending_address() {
        let throttle = Throttle::new();
        for _ in 0..FREE_ATTEMPTS {
            throttle.fail(ip(10));
        }
        assert!(throttle.check(ip(10)).is_err());
        assert!(throttle.check(ip(11)).is_ok());
    }

    #[test]
    fn success_clears_the_record() {
        let throttle = Throttle::new();
        for _ in 0..FREE_ATTEMPTS {
            throttle.fail(ip(10));
        }
        throttle.success(ip(10));
        assert!(throttle.check(ip(10)).is_ok());
    }

    #[test]
    fn lockout_grows_but_is_capped() {
        let throttle = Throttle::new();
        for _ in 0..40 {
            throttle.fail(ip(10));
        }
        let wait = throttle.check(ip(10)).expect_err("locked");
        assert!(wait <= MAX_LOCK);
    }
}
