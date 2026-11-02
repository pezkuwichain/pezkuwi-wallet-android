//! End-to-end tests of the loopback server.
//!
//! The Zagros test syncs the live testnet, so it is ignored by default. Run it with the chain
//! specifications of the relay chain and People:
//!
//! ```sh
//! PEZKUWI_LIGHT_RELAY_SPEC=zagros.json PEZKUWI_LIGHT_PEOPLE_SPEC=people-zagros.json \
//!     cargo test --release -- --ignored --nocapture
//! ```

use pezkuwi_light::engine::Engine;
use std::{
    net::TcpStream,
    time::{Duration, Instant},
};
use tungstenite::{stream::MaybeTlsStream, Message, WebSocket};

type Socket = WebSocket<MaybeTlsStream<TcpStream>>;

fn temporary_dir(name: &str) -> std::path::PathBuf {
    let dir = std::env::temp_dir().join(format!("pezkuwi-light-{name}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    dir
}

fn rpc(socket: &mut Socket, id: u64, method: &str, params: serde_json::Value) {
    let request = serde_json::json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params});
    socket.send(Message::Text(request.to_string())).unwrap();
}

fn next_json(socket: &mut Socket) -> serde_json::Value {
    loop {
        if let Message::Text(text) = socket.read().unwrap() {
            return serde_json::from_str(&text).unwrap();
        }
    }
}

fn answer(socket: &mut Socket, id: u64) -> serde_json::Value {
    loop {
        let message = next_json(socket);
        if message["id"] == id {
            return message;
        }
    }
}

/// `Vec<u8>` SCALE encoding: compact length, then the bytes.
fn scale_bytes(bytes: &[u8]) -> String {
    assert!(bytes.len() < 64, "single-byte compact length only");
    format!("0x{:02x}{}", (bytes.len() as u8) << 2, hex::encode(bytes))
}

#[test]
fn a_path_without_the_token_is_refused() {
    let engine = Engine::start(temporary_dir("refused")).unwrap();
    let base = engine.base_url();
    let (_, token) = base.rsplit_once('/').unwrap();
    let wrong = format!("{}{}", &token[..token.len() - 1], if token.ends_with('0') { '1' } else { '0' });
    let port = base.split(':').nth(2).unwrap().split('/').next().unwrap();

    for url in [
        format!("ws://127.0.0.1:{port}/{wrong}/relay"),
        format!("ws://127.0.0.1:{port}/relay"),
        // The token is right but no chain is registered under the key.
        format!("{base}/relay"),
    ] {
        match tungstenite::connect(&url) {
            Err(tungstenite::Error::Http(response)) => assert_eq!(response.status(), 404, "{url}"),
            other => panic!("{url} was not refused: {:?}", other.map(|_| ())),
        }
    }
}

#[test]
#[ignore = "syncs the live Zagros testnet; needs PEZKUWI_LIGHT_RELAY_SPEC and PEZKUWI_LIGHT_PEOPLE_SPEC"]
fn resolves_kurd_names_on_zagros_people() {
    let relay = std::fs::read_to_string(std::env::var("PEZKUWI_LIGHT_RELAY_SPEC").unwrap()).unwrap();
    let people = std::fs::read_to_string(std::env::var("PEZKUWI_LIGHT_PEOPLE_SPEC").unwrap()).unwrap();
    let db_dir = temporary_dir("zagros");
    let started = Instant::now();

    let engine = Engine::start(db_dir.clone()).unwrap();
    engine.add_chain("relay", &relay, None).unwrap();
    engine.add_chain("people", &people, Some("relay")).unwrap();
    assert!(engine.add_chain("people", &people, Some("relay")).is_err());

    let (mut socket, _) = tungstenite::connect(format!("{}/people", engine.base_url())).unwrap();
    rpc(&mut socket, 1, "chain_subscribeFinalizedHeads", serde_json::json!([]));
    loop {
        if next_json(&mut socket).pointer("/params/result/number").is_some() {
            break;
        }
    }
    println!("first verified People head after {:.1}s", started.elapsed().as_secs_f64());
    let synced_at = std::time::SystemTime::now();

    // Registered on Zagros People by the Meclis grant of 2026-10-31: Governance-owned, active,
    // official. The full node answers the same bytes.
    rpc(&mut socket, 2, "state_call", serde_json::json!(["NavnisanApi_resolve", scale_bytes(b"dks.kurd")]));
    let resolved = answer(&mut socket, 2)["result"].as_str().unwrap().to_owned();
    assert!(
        resolved.starts_with("0x0195c0de27b9a054a3a68cf2bb26abf99071ad46c2485295bb4120185a811a042f"),
        "{resolved}"
    );

    rpc(&mut socket, 3, "state_call", serde_json::json!(["NavnisanApi_resolve", scale_bytes(b"yok-boyle-bir-ad.kurd")]));
    assert_eq!(answer(&mut socket, 3)["result"], "0x00");

    // A second connection to the same chain shares the sync and answers at once.
    let (mut second, _) = tungstenite::connect(format!("{}/people", engine.base_url())).unwrap();
    let asked = Instant::now();
    rpc(&mut second, 4, "state_call", serde_json::json!(["NavnisanApi_resolve", scale_bytes(b"dks.kurd")]));
    assert_eq!(answer(&mut second, 4)["result"].as_str().unwrap(), resolved);
    println!("second connection answered in {:.2}s", asked.elapsed().as_secs_f64());

    // The finalized state is saved for the next start: a save made after the first verified
    // head, from which a new client resumes.
    let deadline = Instant::now() + Duration::from_secs(150);
    for file in ["relay.db", "people.db"] {
        let path = db_dir.join(file);
        while std::fs::metadata(&path).and_then(|m| m.modified()).map_or(true, |at| at < synced_at) {
            assert!(Instant::now() < deadline, "{file} was not saved after the sync");
            std::thread::sleep(Duration::from_millis(500));
        }
        println!("{file}: {} bytes", std::fs::metadata(&path).unwrap().len());
    }

    let restarted = Instant::now();
    let warm = Engine::start(db_dir).unwrap();
    warm.add_chain("relay", &relay, None).unwrap();
    warm.add_chain("people", &people, Some("relay")).unwrap();
    let (mut socket, _) = tungstenite::connect(format!("{}/people", warm.base_url())).unwrap();
    rpc(&mut socket, 5, "state_call", serde_json::json!(["NavnisanApi_resolve", scale_bytes(b"dks.kurd")]));
    assert_eq!(answer(&mut socket, 5)["result"].as_str().unwrap(), resolved);
    println!("warm start resolved after {:.1}s", restarted.elapsed().as_secs_f64());
}
