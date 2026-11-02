//! Embedded PezkuwiChain light client behind a loopback JSON-RPC WebSocket server.
//!
//! The wallet talks to its chains through `SocketService` (a JSON-RPC WebSocket client), so the
//! light client is exposed the same way: `ws://127.0.0.1:<port>/<token>/<chain key>`. Every
//! accepted connection adds its own smoldot chain instance; smoldot shares the underlying sync
//! between instances of the same chain, so a connection costs a JSON-RPC frontend, not a sync.
//!
//! The port is bound to 127.0.0.1 only and every path carries a random token, so other apps on
//! the device cannot drive the client without first learning the token from this process.

use async_tungstenite::tungstenite::{
    handshake::server::{ErrorResponse, Request, Response},
    http::StatusCode,
    Message,
};
use futures_util::{SinkExt as _, StreamExt as _};
use smoldot_light::{
    platform::DefaultPlatform, AddChainConfig, AddChainConfigJsonRpc, AddChainSuccess, ChainId,
    Client, HandleRpcError, JsonRpcResponses,
};
use std::{
    collections::HashMap,
    fs, io,
    net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream},
    num::NonZero,
    path::PathBuf,
    sync::{Arc, Mutex},
    thread,
    time::Duration,
};

type Platform = Arc<DefaultPlatform>;

/// How often the finalized state of each chain is written to disk, so that the next start
/// resumes from it instead of syncing from the chain specification.
const DATABASE_SAVE_INTERVAL: Duration = Duration::from_secs(60);
const FIRST_DATABASE_SAVE: Duration = Duration::from_secs(20);

/// Request id of the database export on a chain's own JSON-RPC frontend.
const DATABASE_REQUEST_ID: &str = "pezkuwi-light-database";

pub struct Engine {
    inner: Arc<Inner>,
    port: u16,
}

struct Inner {
    client: Mutex<Client<Platform>>,
    chains: Mutex<HashMap<String, Registered>>,
    executor: Arc<smol::Executor<'static>>,
    token: String,
    db_dir: PathBuf,
}

struct Registered {
    spec: Arc<str>,
    /// Instance added at registration. It keeps the chain synced while no connection is open and
    /// serves the database export.
    base: ChainId,
    /// Base instance of the relay chain, for a teyrchain.
    relay: Option<ChainId>,
}

#[derive(Debug)]
pub enum Error {
    UnknownRelayChain(String),
    AlreadyRegistered(String),
    AddChain(String),
    Io(io::Error),
}

impl std::fmt::Display for Error {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Error::UnknownRelayChain(key) => write!(f, "relay chain {key} is not registered"),
            Error::AlreadyRegistered(key) => write!(f, "chain {key} is already registered"),
            Error::AddChain(err) => write!(f, "smoldot rejected the chain: {err}"),
            Error::Io(err) => write!(f, "{err}"),
        }
    }
}

impl Engine {
    /// Starts the light client and its loopback server. `db_dir` holds one finalized-state file
    /// per chain key.
    pub fn start(db_dir: PathBuf) -> Result<Engine, Error> {
        fs::create_dir_all(&db_dir).map_err(Error::Io)?;
        let listener =
            TcpListener::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0))).map_err(Error::Io)?;
        let port = listener.local_addr().map_err(Error::Io)?.port();

        let mut token = [0u8; 32];
        rand::RngCore::fill_bytes(&mut rand::rngs::OsRng, &mut token);

        let executor = Arc::new(smol::Executor::new());
        let inner = Arc::new(Inner {
            client: Mutex::new(Client::new(DefaultPlatform::new(
                env!("CARGO_PKG_NAME").into(),
                env!("CARGO_PKG_VERSION").into(),
            ))),
            chains: Mutex::new(HashMap::new()),
            executor: executor.clone(),
            token: hex::encode(token),
            db_dir,
        });

        let listener = smol::Async::new(listener).map_err(Error::Io)?;
        executor.spawn(accept_loop(inner.clone(), listener)).detach();
        thread::Builder::new()
            .name("pezkuwi-light-rpc".into())
            .spawn(move || smol::block_on(executor.run(futures_util::future::pending::<()>())))
            .map_err(Error::Io)?;

        Ok(Engine { inner, port })
    }

    /// `ws://127.0.0.1:<port>/<token>`; a chain is reached at `<this>/<chain key>`.
    pub fn base_url(&self) -> String {
        format!("ws://127.0.0.1:{}/{}", self.port, self.inner.token)
    }

    /// Registers a chain under `key` and starts syncing it. A teyrchain names the key of its
    /// already registered relay chain.
    pub fn add_chain(&self, key: &str, spec: &str, relay_key: Option<&str>) -> Result<(), Error> {
        let mut chains = self.inner.chains.lock().unwrap();
        if chains.contains_key(key) {
            return Err(Error::AlreadyRegistered(key.into()));
        }
        let relay = match relay_key {
            Some(relay_key) => Some(
                chains
                    .get(relay_key)
                    .ok_or_else(|| Error::UnknownRelayChain(relay_key.into()))?
                    .base,
            ),
            None => None,
        };

        let database = fs::read_to_string(self.inner.database_path(key)).unwrap_or_default();
        let AddChainSuccess {
            chain_id,
            json_rpc_responses,
        } = self
            .inner
            .client
            .lock()
            .unwrap()
            .add_chain(AddChainConfig {
                user_data: (),
                specification: spec,
                database_content: &database,
                potential_relay_chains: relay.into_iter(),
                json_rpc: AddChainConfigJsonRpc::Enabled {
                    max_pending_requests: NonZero::new(4).unwrap(),
                    max_subscriptions: 0,
                },
            })
            .map_err(|err| Error::AddChain(err.to_string()))?;

        chains.insert(
            key.into(),
            Registered {
                spec: spec.into(),
                base: chain_id,
                relay,
            },
        );
        self.inner
            .executor
            .spawn(save_database(
                self.inner.clone(),
                key.to_owned(),
                chain_id,
                json_rpc_responses.expect("JSON-RPC is enabled"),
            ))
            .detach();
        Ok(())
    }
}

impl Inner {
    fn database_path(&self, key: &str) -> PathBuf {
        database_path(&self.db_dir, key)
    }
}

fn database_path(db_dir: &std::path::Path, key: &str) -> PathBuf {
    // Keys come from the app (genesis hashes); keep them from escaping the directory anyway.
    let file: String = key
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() { c } else { '_' })
        .collect();
    db_dir.join(format!("{file}.db"))
}

/// Constant-time comparison, so the token cannot be guessed byte by byte from timing.
fn token_matches(token: &str, candidate: &str) -> bool {
    let (a, b) = (token.as_bytes(), candidate.as_bytes());
    a.len() == b.len() && a.iter().zip(b).fold(0u8, |acc, (x, y)| acc | (x ^ y)) == 0
}

async fn accept_loop(inner: Arc<Inner>, listener: smol::Async<TcpListener>) {
    loop {
        match listener.accept().await {
            Ok((stream, _)) => inner.executor.spawn(serve(inner.clone(), stream)).detach(),
            Err(err) => {
                log::warn!("loopback accept failed: {err}");
                smol::Timer::after(Duration::from_millis(100)).await;
            }
        }
    }
}

/// Maps `/<token>/<key>` to a registered chain key, rejecting everything else during the
/// WebSocket handshake.
fn route(token: &str, is_registered: impl Fn(&str) -> bool, path: &str) -> Option<String> {
    let (candidate, key) = path.strip_prefix('/')?.split_once('/')?;
    (token_matches(token, candidate) && is_registered(key)).then(|| key.to_owned())
}

async fn serve(inner: Arc<Inner>, stream: smol::Async<TcpStream>) {
    let mut routed = None;
    let handshake = async_tungstenite::accept_hdr_async(stream, |request: &Request, response: Response| {
        routed = route(
            &inner.token,
            |key| inner.chains.lock().unwrap().contains_key(key),
            request.uri().path(),
        );
        if routed.is_some() {
            Ok(response)
        } else {
            let mut rejection = ErrorResponse::new(None);
            *rejection.status_mut() = StatusCode::NOT_FOUND;
            Err(rejection)
        }
    })
    .await;
    let (Ok(socket), Some(key)) = (handshake, routed) else {
        return;
    };

    let (chain_id, mut responses) = {
        let chains = inner.chains.lock().unwrap();
        let registered = &chains[&key];
        let added = inner.client.lock().unwrap().add_chain(AddChainConfig {
            user_data: (),
            specification: &registered.spec,
            // The base instance already holds the chain; smoldot shares it with this one.
            database_content: "",
            potential_relay_chains: registered.relay.into_iter(),
            json_rpc: AddChainConfigJsonRpc::Enabled {
                max_pending_requests: NonZero::new(128).unwrap(),
                max_subscriptions: 1024,
            },
        });
        match added {
            Ok(success) => (success.chain_id, success.json_rpc_responses.expect("JSON-RPC is enabled")),
            Err(err) => {
                log::warn!("{key}: cannot open a connection instance: {err}");
                return;
            }
        }
    };

    let (mut sink, mut source) = socket.split();
    let (overflow_tx, overflow_rx) = smol::channel::unbounded::<String>();

    let inbound = async {
        while let Some(Ok(message)) = source.next().await {
            let request = match message {
                Message::Text(text) => text,
                Message::Binary(bytes) => match String::from_utf8(bytes) {
                    Ok(text) => text,
                    Err(_) => continue,
                },
                Message::Close(_) => break,
                _ => continue,
            };
            let refused = inner.client.lock().unwrap().json_rpc_request(request, chain_id);
            if let Err(HandleRpcError::TooManyPendingRequests { json_rpc_request }) = refused {
                if let Some(error) = busy_error(&json_rpc_request) {
                    let _ = overflow_tx.send(error).await;
                }
            }
        }
    };
    let outbound = async {
        loop {
            let response = futures_util::future::select(
                Box::pin(responses.next()),
                Box::pin(overflow_rx.recv()),
            )
            .await;
            let text = match response {
                futures_util::future::Either::Left((Some(text), _)) => text,
                futures_util::future::Either::Right((Ok(text), _)) => text,
                _ => break,
            };
            if sink.send(Message::Text(text)).await.is_err() {
                break;
            }
        }
    };
    futures_util::future::select(Box::pin(inbound), Box::pin(outbound)).await;

    let () = inner.client.lock().unwrap().remove_chain(chain_id);
}

/// JSON-RPC error for a request smoldot refused because its queue is full, so the caller gets an
/// answer instead of waiting forever. Notifications (no id) get none.
fn busy_error(request: &str) -> Option<String> {
    let id = serde_json::from_str::<serde_json::Value>(request).ok()?.get("id")?.clone();
    Some(
        serde_json::json!({
            "jsonrpc": "2.0",
            "id": id,
            "error": { "code": -32000, "message": "light client is busy, retry" },
        })
        .to_string(),
    )
}

/// Periodically writes the chain's finalized state to disk through its base instance.
async fn save_database(
    inner: Arc<Inner>,
    key: String,
    chain_id: ChainId,
    mut responses: JsonRpcResponses<Platform>,
) {
    let request = serde_json::json!({
        "jsonrpc": "2.0",
        "id": DATABASE_REQUEST_ID,
        "method": "chainHead_unstable_finalizedDatabase",
        "params": [],
    })
    .to_string();
    let path = inner.database_path(&key);
    let mut wait = FIRST_DATABASE_SAVE;
    loop {
        smol::Timer::after(wait).await;
        wait = DATABASE_SAVE_INTERVAL;
        if inner
            .client
            .lock()
            .unwrap()
            .json_rpc_request(request.clone(), chain_id)
            .is_err()
        {
            continue;
        }
        while let Some(response) = responses.next().await {
            let response: serde_json::Value = match serde_json::from_str(&response) {
                Ok(value) => value,
                Err(_) => continue,
            };
            if response.get("id").and_then(|id| id.as_str()) != Some(DATABASE_REQUEST_ID) {
                continue;
            }
            if let Some(database) = response.get("result").and_then(|db| db.as_str()) {
                if let Err(err) = write_atomically(&path, database) {
                    log::warn!("{key}: cannot save the light client database: {err}");
                }
            }
            break;
        }
    }
}

/// Writes through a temporary file and a rename, so a crash never leaves a torn database.
fn write_atomically(path: &std::path::Path, contents: &str) -> io::Result<()> {
    let temporary = path.with_extension("db.tmp");
    fs::write(&temporary, contents)?;
    fs::rename(&temporary, path)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn route_requires_the_token_and_a_registered_chain() {
        let token = "ab".repeat(32);
        let route = |path: &str| route(&token, |key| key == "people", path);
        assert_eq!(route(&format!("/{token}/people")), Some("people".into()));
        assert_eq!(route(&format!("/{token}/relay")), None);
        assert_eq!(route(&format!("/{}/people", "ac".repeat(32))), None);
        assert_eq!(route(&format!("/{}/people", &token[1..])), None);
        assert_eq!(route(&format!("/{token}people")), None);
        assert_eq!(route("/people"), None);
        assert_eq!(route(&format!("{token}/people")), None);
    }

    #[test]
    fn database_path_stays_inside_the_directory() {
        assert_eq!(
            database_path(std::path::Path::new("/data"), "../../etc/passwd"),
            PathBuf::from("/data/______etc_passwd.db")
        );
    }

    #[test]
    fn busy_error_answers_the_request_id() {
        let error = busy_error(r#"{"jsonrpc":"2.0","id":7,"method":"state_call","params":[]}"#).unwrap();
        let error: serde_json::Value = serde_json::from_str(&error).unwrap();
        assert_eq!(error["id"], 7);
        assert_eq!(error["error"]["code"], -32000);
        assert_eq!(busy_error(r#"{"jsonrpc":"2.0","method":"x"}"#), None);
    }
}
