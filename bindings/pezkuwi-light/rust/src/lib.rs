//! JNI surface of the embedded PezkuwiChain light client. See [`engine`] for how it works.

pub mod engine;
pub mod platform;

use engine::Engine;
use jni::objects::{GlobalRef, JObject, JObjectArray, JString};
use jni::sys::{jboolean, jstring, JNI_TRUE};
use jni::{JNIEnv, JavaVM};
use platform::Resolve;
use std::{io, net::IpAddr, sync::Arc, sync::OnceLock};

/// One light client per process: smoldot shares chains between its instances, and the app
/// reaches it through a single loopback server.
static ENGINE: OnceLock<Result<Engine, String>> = OnceLock::new();

fn engine() -> Option<&'static Engine> {
    ENGINE.get().and_then(|engine| engine.as_ref().ok())
}

fn throw(env: &mut JNIEnv, message: &str) {
    let _ = env.throw_new("java/lang/IllegalStateException", message);
}

fn read_string(env: &mut JNIEnv, value: &JString) -> Option<String> {
    env.get_string(value).ok().map(Into::into)
}

/// Resolves peer host names through the app's `HostResolver`, so the light client follows the
/// app's DNS policy (DNS over HTTPS first).
struct AppResolver {
    vm: JavaVM,
    resolver: GlobalRef,
}

impl Resolve for AppResolver {
    fn resolve(&self, host: &str) -> io::Result<Vec<IpAddr>> {
        // Blocking-pool threads are reused, so they stay attached instead of paying an attach
        // per lookup.
        let mut env = self
            .vm
            .attach_current_thread_permanently()
            .map_err(|_| lookup_error(host, "cannot attach to the JVM"))?;
        // The frame's own result must convert from `jni::errors::Error`; the lookup's outcome
        // travels inside it.
        env.with_local_frame(8, |env| -> jni::errors::Result<_> {
            Ok(lookup(env, &self.resolver, host))
        })
        .unwrap_or_else(|_| Err(lookup_error(host, "JNI frame failed")))
    }
}

fn lookup_error(host: &str, what: &str) -> io::Error {
    io::Error::new(io::ErrorKind::Other, format!("{host}: {what}"))
}

/// Calls `String[] resolve(String)` on the app's resolver. A thrown exception (an unknown host)
/// is cleared and reported as an error.
fn lookup(env: &mut JNIEnv, resolver: &GlobalRef, host: &str) -> io::Result<Vec<IpAddr>> {
    let failed = |what| lookup_error(host, what);
    let name = env.new_string(host).map_err(|_| failed("invalid name"))?;
    let answer = env.call_method(
        resolver,
        "resolve",
        "(Ljava/lang/String;)[Ljava/lang/String;",
        &[(&name).into()],
    );
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_clear();
        return Err(failed("no address"));
    }
    let answer: JObjectArray = answer
        .and_then(|value| value.l())
        .map_err(|_| failed("resolver failed"))?
        .into();
    let count = env.get_array_length(&answer).map_err(|_| failed("bad answer"))?;
    let mut addresses = Vec::new();
    for index in 0..count {
        let element: JString = env
            .get_object_array_element(&answer, index)
            .map_err(|_| failed("bad answer"))?
            .into();
        if let Some(address) = read_string(env, &element).and_then(|text| text.parse().ok()) {
            addresses.push(address);
        }
    }
    Ok(addresses)
}

/// Starts the light client (once per process) and returns its loopback base URL.
#[no_mangle]
pub extern "system" fn Java_io_novafoundation_nova_lightclient_PezkuwiLight_start<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    database_dir: JString<'local>,
    resolver: JObject<'local>,
) -> jstring {
    let Some(database_dir) = read_string(&mut env, &database_dir) else {
        throw(&mut env, "invalid database directory");
        return std::ptr::null_mut();
    };
    let resolver = match (env.get_java_vm(), env.new_global_ref(&resolver)) {
        (Ok(vm), Ok(resolver)) => AppResolver { vm, resolver },
        _ => {
            throw(&mut env, "invalid resolver");
            return std::ptr::null_mut();
        }
    };
    let started = ENGINE.get_or_init(|| {
        #[cfg(target_os = "android")]
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("PezkuwiLight"),
        );
        Engine::start(database_dir.into(), Arc::new(resolver)).map_err(|err| err.to_string())
    });
    match started {
        Ok(engine) => match env.new_string(engine.base_url()) {
            Ok(url) => url.into_raw(),
            Err(_) => std::ptr::null_mut(),
        },
        Err(err) => {
            throw(&mut env, err);
            std::ptr::null_mut()
        }
    }
}

/// Registers a chain under `key`; a teyrchain passes its relay chain's key.
#[no_mangle]
pub extern "system" fn Java_io_novafoundation_nova_lightclient_PezkuwiLight_addChain<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    key: JString<'local>,
    chain_spec: JString<'local>,
    relay_key: JString<'local>,
) {
    let Some(engine) = engine() else {
        throw(&mut env, "light client is not started");
        return;
    };
    let (Some(key), Some(chain_spec)) =
        (read_string(&mut env, &key), read_string(&mut env, &chain_spec))
    else {
        throw(&mut env, "invalid chain key or specification");
        return;
    };
    let relay_key = if relay_key.is_null() {
        None
    } else {
        read_string(&mut env, &relay_key)
    };
    if let Err(err) = engine.add_chain(&key, &chain_spec, relay_key.as_deref()) {
        throw(&mut env, &err.to_string());
    }
}

/// Stops syncing the chain registered under `key`; a relay chain goes after its teyrchains.
#[no_mangle]
pub extern "system" fn Java_io_novafoundation_nova_lightclient_PezkuwiLight_removeChain<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    key: JString<'local>,
) {
    let Some(engine) = engine() else {
        throw(&mut env, "light client is not started");
        return;
    };
    let Some(key) = read_string(&mut env, &key) else {
        throw(&mut env, "invalid chain key");
        return;
    };
    if let Err(err) = engine.remove_chain(&key) {
        throw(&mut env, &err.to_string());
    }
}

/// Restricts new peer connections to secure WebSockets.
#[no_mangle]
pub extern "system" fn Java_io_novafoundation_nova_lightclient_PezkuwiLight_setSecureOnly<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    secure_only: jboolean,
) {
    match engine() {
        Some(engine) => engine.set_secure_only(secure_only == JNI_TRUE),
        None => throw(&mut env, "light client is not started"),
    }
}
