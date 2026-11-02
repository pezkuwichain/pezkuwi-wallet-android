//! smoldot platform for the wallet: secure WebSockets and an app-supplied name resolver.
//!
//! smoldot's own `DefaultPlatform` cannot open `wss` connections and resolves names through the
//! system resolver. Where our domains are blocked, both fail: a poisoned DNS answer decides where
//! the client connects, and plain libp2p TCP is recognisable on the wire. This platform:
//! - opens `/dns/<host>/tcp/<port>/wss` over TLS (rustls, Mozilla roots), so a connection looks
//!   like any HTTPS WebSocket;
//! - resolves every name through [`Resolve`], which the app backs with its DNS-over-HTTPS
//!   resolver, so the light client and the rest of the app follow one DNS policy;
//! - can be restricted to secure WebSockets only ([`PezkuwiPlatform::set_secure_only`]).
//!
//! The peer's identity is still checked end to end by libp2p Noise, so neither the TLS terminator
//! in front of a node nor a CDN can alter what the client verifies.

use futures_util::{future, AsyncRead, AsyncWrite, FutureExt as _};
use smoldot_light::platform::{
    with_buffers, Address, ConnectionType, LogLevel, MultiStreamAddress,
    MultiStreamWebRtcConnection, PlatformRef, SubstreamDirection,
};
use std::{
    borrow::Cow,
    future::Future,
    fmt::{self, Write as _},
    io,
    net::{self, SocketAddr},
    panic,
    pin::Pin,
    sync::{
        atomic::{AtomicBool, Ordering},
        Arc,
    },
    thread,
    time::{Duration, Instant, UNIX_EPOCH},
};

/// Resolves a host name to addresses. Called on a blocking thread.
pub trait Resolve: Send + Sync + 'static {
    fn resolve(&self, host: &str) -> io::Result<Vec<net::IpAddr>>;
}

/// The operating system's resolver, for tests and hosts without an app resolver.
pub struct SystemResolver;

impl Resolve for SystemResolver {
    fn resolve(&self, host: &str) -> io::Result<Vec<net::IpAddr>> {
        use std::net::ToSocketAddrs as _;
        Ok((host, 0).to_socket_addrs()?.map(|addr| addr.ip()).collect())
    }
}

/// Bound on each TCP connection attempt, so an address that silently drops packets does not hold
/// a connection slot.
const CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Clone)]
pub struct PezkuwiPlatform(Arc<Shared>);

struct Shared {
    client_name: String,
    client_version: String,
    executor: Arc<smol::Executor<'static>>,
    resolver: Arc<dyn Resolve>,
    tls: futures_rustls::TlsConnector,
    secure_only: AtomicBool,
}

// smoldot requires `UnwindSafe`. A panic inside a task is caught at the task boundary
// (`spawn_task`), and nothing here is left half-updated across it: the resolver is called
// through `&self`, and the TLS configuration is immutable.
impl panic::UnwindSafe for PezkuwiPlatform {}
impl panic::RefUnwindSafe for PezkuwiPlatform {}

impl PezkuwiPlatform {
    pub fn new(client_name: String, client_version: String, resolver: Arc<dyn Resolve>) -> Self {
        let executor = Arc::new(smol::Executor::new());
        let threads = thread::available_parallelism().map_or(2, |n| n.get().min(4));
        for n in 0..threads {
            let executor = executor.clone();
            thread::Builder::new()
                .name(format!("pezkuwi-light-{n}"))
                .spawn(move || smol::block_on(executor.run(future::pending::<()>())))
                .expect("cannot spawn a light client thread");
        }

        let roots = rustls::RootCertStore {
            roots: webpki_roots::TLS_SERVER_ROOTS.to_vec(),
        };
        let tls = rustls::ClientConfig::builder_with_provider(Arc::new(
            rustls::crypto::ring::default_provider(),
        ))
        .with_safe_default_protocol_versions()
        .expect("ring supports the default protocol versions")
        .with_root_certificates(roots)
        .with_no_client_auth();

        PezkuwiPlatform(Arc::new(Shared {
            client_name,
            client_version,
            executor,
            resolver,
            tls: futures_rustls::TlsConnector::from(Arc::new(tls)),
            secure_only: AtomicBool::new(false),
        }))
    }

    /// Restricts new connections to secure WebSockets. Open connections are kept.
    pub fn set_secure_only(&self, secure_only: bool) {
        self.0.secure_only.store(secure_only, Ordering::Relaxed);
    }
}

/// A connected byte stream: plain TCP, WebSocket, or WebSocket over TLS.
pub trait Socket: AsyncRead + AsyncWrite + Send {}
impl<T: AsyncRead + AsyncWrite + Send> Socket for T {}

type BoxedSocket = Pin<Box<dyn Socket>>;
type Connecting = future::BoxFuture<'static, io::Result<BoxedSocket>>;

#[pin_project::pin_project]
pub struct Stream(#[pin] with_buffers::WithBuffers<Connecting, BoxedSocket, Instant>);

/// Where to connect and what to speak once connected.
struct Target {
    host: Host,
    port: u16,
    websocket: Option<WebSocket>,
}

enum Host {
    Ip(net::IpAddr),
    Name(String),
}

struct WebSocket {
    /// `Host` header value.
    authority: String,
    /// TLS server name, for `wss`.
    tls_name: Option<String>,
}

fn target(address: Address) -> Target {
    match address {
        Address::TcpIp { ip, port } => Target {
            host: Host::Ip(ip),
            port,
            websocket: None,
        },
        Address::TcpDns { hostname, port } => Target {
            host: Host::Name(hostname.into()),
            port,
            websocket: None,
        },
        Address::WebSocketIp { ip, port } => {
            Target {
                host: Host::Ip(ip),
                port,
                websocket: Some(WebSocket {
                    authority: SocketAddr::from((ip, port)).to_string(),
                    tls_name: None,
                }),
            }
        }
        Address::WebSocketDns {
            hostname,
            port,
            secure,
        } => Target {
            host: Host::Name(hostname.into()),
            port,
            websocket: Some(WebSocket {
                authority: if (secure && port == 443) || (!secure && port == 80) {
                    hostname.into()
                } else {
                    format!("{hostname}:{port}")
                },
                tls_name: secure.then(|| hostname.into()),
            }),
        },
    }
}

/// IPv4 first: a resolver may return IPv6 addresses on a network without an IPv6 route, and a
/// dead route would otherwise cost a full connect timeout before every connection.
fn ordered(mut addresses: Vec<net::IpAddr>) -> Vec<net::IpAddr> {
    let mut seen = std::collections::HashSet::new();
    addresses.retain(|address| seen.insert(*address));
    addresses.sort_by_key(|address| address.is_ipv6());
    addresses
}

async fn connect_tcp(shared: Arc<Shared>, host: Host, port: u16) -> io::Result<smol::net::TcpStream> {
    let addresses = match host {
        Host::Ip(address) => vec![address],
        Host::Name(name) => {
            let resolver = shared.resolver.clone();
            ordered(smol::unblock(move || resolver.resolve(&name)).await?)
        }
    };
    let mut last_error = io::Error::new(io::ErrorKind::NotFound, "the name has no address");
    for address in addresses {
        let attempt = smol::net::TcpStream::connect(SocketAddr::from((address, port)));
        let timeout = async {
            smol::Timer::after(CONNECT_TIMEOUT).await;
            Err(io::Error::new(io::ErrorKind::TimedOut, "connect timed out"))
        };
        match future::select(Box::pin(attempt), Box::pin(timeout)).await.factor_first().0 {
            Ok(socket) => {
                let _ = socket.set_nodelay(true);
                return Ok(socket);
            }
            Err(err) => last_error = err,
        }
    }
    Err(last_error)
}

async fn connect(shared: Arc<Shared>, target: Target) -> io::Result<BoxedSocket> {
    let tcp = connect_tcp(shared.clone(), target.host, target.port).await?;
    let Some(websocket) = target.websocket else {
        return Ok(Box::pin(tcp));
    };
    match &websocket.tls_name {
        None => websocket_handshake(tcp, &websocket.authority).await,
        Some(name) => {
            let name = rustls::pki_types::ServerName::try_from(name.clone())
                .map_err(|err| io::Error::new(io::ErrorKind::InvalidInput, err))?;
            let tls = shared.tls.connect(name, tcp).await?;
            websocket_handshake(tls, &websocket.authority).await
        }
    }
}

async fn websocket_handshake<T>(socket: T, authority: &str) -> io::Result<BoxedSocket>
where
    T: AsyncRead + AsyncWrite + Send + Unpin + 'static,
{
    let connection = smoldot::libp2p::websocket::websocket_client_handshake(
        smoldot::libp2p::websocket::Config {
            tcp_socket: socket,
            host: authority,
            url: "/",
        },
    )
    .await?;
    Ok(Box::pin(connection))
}

impl PlatformRef for PezkuwiPlatform {
    type Delay = future::Map<smol::Timer, fn(Instant) -> ()>;
    type Instant = Instant;
    type MultiStream = std::convert::Infallible;
    type Stream = Stream;
    type StreamConnectFuture = future::Ready<Self::Stream>;
    type MultiStreamConnectFuture = future::Pending<MultiStreamWebRtcConnection<Self::MultiStream>>;
    type ReadWriteAccess<'a> = with_buffers::ReadWriteAccess<'a, Instant>;
    type StreamUpdateFuture<'a> = future::BoxFuture<'a, ()>;
    type StreamErrorRef<'a> = &'a io::Error;
    type NextSubstreamFuture<'a> = future::Pending<Option<(Self::Stream, SubstreamDirection)>>;

    fn now_from_unix_epoch(&self) -> Duration {
        UNIX_EPOCH.elapsed().expect("the clock is set after 1970")
    }

    fn now(&self) -> Instant {
        Instant::now()
    }

    fn fill_random_bytes(&self, buffer: &mut [u8]) {
        rand::RngCore::fill_bytes(&mut rand::thread_rng(), buffer);
    }

    fn sleep(&self, duration: Duration) -> Self::Delay {
        smol::Timer::after(duration).map(drop as fn(Instant))
    }

    fn sleep_until(&self, when: Instant) -> Self::Delay {
        smol::Timer::at(when).map(drop as fn(Instant))
    }

    fn spawn_task(&self, _task_name: Cow<str>, task: impl Future<Output = ()> + Send + 'static) {
        self.0
            .executor
            .spawn(panic::AssertUnwindSafe(task).catch_unwind().map(drop))
            .detach();
    }

    fn log<'a>(
        &self,
        log_level: LogLevel,
        log_target: &'a str,
        message: &'a str,
        key_values: impl Iterator<Item = (&'a str, &'a dyn fmt::Display)>,
    ) {
        let level = match log_level {
            LogLevel::Error => log::Level::Error,
            LogLevel::Warn => log::Level::Warn,
            LogLevel::Info => log::Level::Info,
            LogLevel::Debug => log::Level::Debug,
            LogLevel::Trace => log::Level::Trace,
        };
        if !log::log_enabled!(target: log_target, level) {
            return;
        }
        let mut line = String::from(message);
        for (n, (key, value)) in key_values.enumerate() {
            let _ = write!(line, "{}{key}={value}", if n == 0 { "; " } else { ", " });
        }
        log::log!(target: log_target, level, "{line}");
    }

    fn client_name(&self) -> Cow<'_, str> {
        Cow::Borrowed(&self.0.client_name)
    }

    fn client_version(&self) -> Cow<'_, str> {
        Cow::Borrowed(&self.0.client_version)
    }

    fn supports_connection_type(&self, connection_type: ConnectionType) -> bool {
        match connection_type {
            ConnectionType::WebSocketDns { secure: true, .. } => true,
            ConnectionType::TcpIpv4
            | ConnectionType::TcpIpv6
            | ConnectionType::TcpDns
            | ConnectionType::WebSocketIpv4 { .. }
            | ConnectionType::WebSocketIpv6 { .. }
            | ConnectionType::WebSocketDns { secure: false, .. } => {
                !self.0.secure_only.load(Ordering::Relaxed)
            }
            // WebRTC needs a multi-stream implementation, which a native client has no use for.
            _ => false,
        }
    }

    fn connect_stream(&self, address: Address) -> Self::StreamConnectFuture {
        let connecting: Connecting = Box::pin(connect(self.0.clone(), target(address)));
        future::ready(Stream(with_buffers::WithBuffers::new(connecting)))
    }

    fn connect_multistream(&self, _address: MultiStreamAddress) -> Self::MultiStreamConnectFuture {
        unreachable!("no multi-stream connection type is supported")
    }

    fn open_out_substream(&self, connection: &mut Self::MultiStream) {
        match *connection {}
    }

    fn next_substream<'a>(
        &self,
        connection: &'a mut Self::MultiStream,
    ) -> Self::NextSubstreamFuture<'a> {
        match *connection {}
    }

    fn read_write_access<'a>(
        &self,
        stream: Pin<&'a mut Self::Stream>,
    ) -> Result<Self::ReadWriteAccess<'a>, &'a io::Error> {
        stream.project().0.read_write_access(Instant::now())
    }

    fn wait_read_write_again<'a>(&self, stream: Pin<&'a mut Self::Stream>) -> Self::StreamUpdateFuture<'a> {
        Box::pin(
            stream
                .project()
                .0
                .wait_read_write_again(|when| smol::Timer::at(when).map(drop)),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn platform() -> PezkuwiPlatform {
        PezkuwiPlatform::new("test".into(), "0".into(), Arc::new(SystemResolver))
    }

    #[test]
    fn secure_only_admits_nothing_but_secure_websockets() {
        let platform = platform();
        let plain = [
            ConnectionType::TcpIpv4,
            ConnectionType::TcpIpv6,
            ConnectionType::TcpDns,
            ConnectionType::WebSocketIpv4 { remote_is_localhost: false },
            ConnectionType::WebSocketIpv6 { remote_is_localhost: false },
            ConnectionType::WebSocketDns { secure: false, remote_is_localhost: false },
        ];
        let secure = ConnectionType::WebSocketDns { secure: true, remote_is_localhost: false };
        assert!(platform.supports_connection_type(secure));
        assert!(plain.iter().all(|kind| platform.supports_connection_type(*kind)));

        platform.set_secure_only(true);
        assert!(platform.supports_connection_type(secure));
        assert!(plain.iter().all(|kind| !platform.supports_connection_type(*kind)));
    }

    #[test]
    fn websocket_targets_carry_the_right_host_header_and_tls_name() {
        let wss = target(Address::WebSocketDns { hostname: "node.example", port: 443, secure: true });
        let ws = wss.websocket.unwrap();
        assert_eq!((ws.authority.as_str(), ws.tls_name.as_deref()), ("node.example", Some("node.example")));

        let wss = target(Address::WebSocketDns { hostname: "node.example", port: 8443, secure: true });
        assert_eq!(wss.websocket.unwrap().authority, "node.example:8443");

        let ws = target(Address::WebSocketDns { hostname: "node.example", port: 30335, secure: false });
        let ws = ws.websocket.unwrap();
        assert_eq!((ws.authority.as_str(), ws.tls_name), ("node.example:30335", None));

        assert!(target(Address::TcpDns { hostname: "node.example", port: 30333 }).websocket.is_none());
    }

    #[test]
    fn ipv4_is_tried_before_ipv6() {
        let v6: net::IpAddr = "2001:db8::1".parse().unwrap();
        let v4: net::IpAddr = "192.0.2.1".parse().unwrap();
        let other: net::IpAddr = "192.0.2.2".parse().unwrap();
        assert_eq!(ordered(vec![v6, v4, other, v4]), vec![v4, other, v6]);
    }
}
