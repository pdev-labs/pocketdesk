//! LAN discovery.
//!
//! The host broadcasts a small JSON beacon every three seconds on UDP 8766 and
//! answers `{"t":"discover"}` probes immediately. The phone lists whatever it
//! hears, using the packet's source address (not a self-reported one), so the
//! address is always reachable from the interface the beacon arrived on.
//!
//! The pairing token is never broadcast; discovery only saves typing an IP.

use std::time::Duration;

use tokio::net::UdpSocket;

use crate::config;

pub const PORT: u16 = 8766;

pub fn spawn(http_port: u16, version: &'static str) {
    let name = crate::pairing::hostname();

    tokio::spawn(async move {
        let socket = match UdpSocket::bind(("0.0.0.0", PORT)).await {
            Ok(socket) => socket,
            Err(err) => {
                tracing::debug!("discovery disabled: cannot bind UDP {PORT}: {err}");
                return;
            }
        };
        if let Err(err) = socket.set_broadcast(true) {
            tracing::debug!("discovery: cannot enable broadcast: {err}");
            return;
        }

        let beacon = serde_json::json!({
            "app": "pocketdesk",
            "t": "beacon",
            "name": name,
            "port": http_port,
            "version": version,
        })
        .to_string();

        let mut tick = tokio::time::interval(Duration::from_secs(3));
        let mut buffer = [0u8; 512];

        loop {
            tokio::select! {
                _ = tick.tick() => {
                    let _ = socket
                        .send_to(beacon.as_bytes(), ("255.255.255.255", PORT))
                        .await;
                    if let Some(broadcast) = subnet_broadcast() {
                        let _ = socket
                            .send_to(beacon.as_bytes(), (broadcast, PORT))
                            .await;
                    }
                }
                received = socket.recv_from(&mut buffer) => {
                    let Ok((len, peer)) = received else { continue };
                    let text = String::from_utf8_lossy(&buffer[..len]);
                    if text.contains("pocketdesk") && text.contains("discover") {
                        // Unicast reply: the phone gets an answer without
                        // waiting for the next beacon.
                        let _ = socket.send_to(beacon.as_bytes(), peer).await;
                    }
                }
            }
        }
    });
}

/// The /24 broadcast of the default-route interface. Limited broadcast covers
/// most home networks; this additionally helps on interfaces that drop it.
fn subnet_broadcast() -> Option<std::net::Ipv4Addr> {
    match config::lan_ip()? {
        std::net::IpAddr::V4(ip) if !ip.is_loopback() => {
            let octets = ip.octets();
            Some(std::net::Ipv4Addr::new(
                octets[0],
                octets[1],
                octets[2],
                255,
            ))
        }
        _ => None,
    }
}
