//! JNI surface of the embedded PezkuwiChain light client. See [`engine`] for how it works.

pub mod engine;

use engine::Engine;
use jni::objects::{JObject, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use std::sync::OnceLock;

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

/// Starts the light client (once per process) and returns its loopback base URL.
#[no_mangle]
pub extern "system" fn Java_io_novafoundation_nova_lightclient_PezkuwiLight_start<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    database_dir: JString<'local>,
) -> jstring {
    let Some(database_dir) = read_string(&mut env, &database_dir) else {
        throw(&mut env, "invalid database directory");
        return std::ptr::null_mut();
    };
    let started = ENGINE.get_or_init(|| {
        #[cfg(target_os = "android")]
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag("PezkuwiLight"),
        );
        Engine::start(database_dir.into()).map_err(|err| err.to_string())
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
