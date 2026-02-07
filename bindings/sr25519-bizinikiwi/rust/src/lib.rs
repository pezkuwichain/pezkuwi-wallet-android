use jni::JNIEnv;
use jni::objects::{JByteArray, JClass};
use jni::sys::jbyteArray;
use schnorrkel::{ExpansionMode, Keypair, MiniSecretKey, PublicKey, SecretKey, Signature};
use schnorrkel::context::signing_context;

/// Pezkuwi signing context - different from standard "substrate"
const BIZINIKIWI_CTX: &[u8] = b"bizinikiwi";

const KEYPAIR_LENGTH: usize = 96;
const SECRET_KEY_LENGTH: usize = 64;
const PUBLIC_KEY_LENGTH: usize = 32;
const SIGNATURE_LENGTH: usize = 64;
const SEED_LENGTH: usize = 32;

fn create_from_seed(seed: &[u8]) -> Keypair {
    match MiniSecretKey::from_bytes(seed) {
        Ok(mini) => mini.expand_to_keypair(ExpansionMode::Ed25519),
        Err(_) => panic!("Invalid seed provided"),
    }
}

fn create_from_pair(pair: &[u8]) -> Keypair {
    match Keypair::from_bytes(pair) {
        Ok(kp) => kp,
        Err(_) => panic!("Invalid keypair provided"),
    }
}

/// Sign a message using bizinikiwi context
#[no_mangle]
pub extern "system" fn Java_io_novafoundation_nova_sr25519_BizinikiwSr25519_sign<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    public_key: JByteArray<'local>,
    secret_key: JByteArray<'local>,
    message: JByteArray<'local>,
) -> jbyteArray {
    let public_vec = env.convert_byte_array(&public_key).expect("Invalid public key");
    let secret_vec = env.convert_byte_array(&secret_key).expect("Invalid secret key");
    let message_vec = env.convert_byte_array(&message).expect("Invalid message");

    let secret = SecretKey::from_bytes(&secret_vec).expect("Invalid secret key bytes");
    let public = PublicKey::from_bytes(&public_vec).expect("Invalid public key bytes");

    let context = signing_context(BIZINIKIWI_CTX);
    let signature = secret.sign(context.bytes(&message_vec), &public);

    let output = env.byte_array_from_slice(signature.to_bytes().as_ref())
        .expect("Failed to create signature array");

    output.into_raw()
}

/// Verify a signature using bizinikiwi context
#[no_mangle]
pub extern "system" fn Java_io_novafoundation_nova_sr25519_BizinikiwSr25519_verify<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    signature: JByteArray<'local>,
    message: JByteArray<'local>,
    public_key: JByteArray<'local>,
) -> bool {
    let sig_vec = env.convert_byte_array(&signature).expect("Invalid signature");
    let msg_vec = env.convert_byte_array(&message).expect("Invalid message");
    let pub_vec = env.convert_byte_array(&public_key).expect("Invalid public key");

    let sig = match Signature::from_bytes(&sig_vec) {
        Ok(s) => s,
        Err(_) => return false,
    };

    let public = match PublicKey::from_bytes(&pub_vec) {
        Ok(p) => p,
        Err(_) => return false,
    };

    let context = signing_context(BIZINIKIWI_CTX);
    public.verify(context.bytes(&msg_vec), &sig).is_ok()
}

/// Generate keypair from seed
#[no_mangle]
pub extern "system" fn Java_io_novafoundation_nova_sr25519_BizinikiwSr25519_keypairFromSeed<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    seed: JByteArray<'local>,
) -> jbyteArray {
    let seed_vec = env.convert_byte_array(&seed).expect("Invalid seed");

    let keypair = create_from_seed(&seed_vec);

    let output = env.byte_array_from_slice(&keypair.to_bytes())
        .expect("Failed to create keypair array");

    output.into_raw()
}

/// Get public key from keypair
#[no_mangle]
pub extern "system" fn Java_io_novafoundation_nova_sr25519_BizinikiwSr25519_publicKeyFromKeypair<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    keypair: JByteArray<'local>,
) -> jbyteArray {
    let keypair_vec = env.convert_byte_array(&keypair).expect("Invalid keypair");

    let kp = create_from_pair(&keypair_vec);

    let output = env.byte_array_from_slice(kp.public.to_bytes().as_ref())
        .expect("Failed to create public key array");

    output.into_raw()
}

/// Get secret key from keypair (64 bytes: 32 key + 32 nonce)
#[no_mangle]
pub extern "system" fn Java_io_novafoundation_nova_sr25519_BizinikiwSr25519_secretKeyFromKeypair<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    keypair: JByteArray<'local>,
) -> jbyteArray {
    let keypair_vec = env.convert_byte_array(&keypair).expect("Invalid keypair");

    let kp = create_from_pair(&keypair_vec);

    let output = env.byte_array_from_slice(&kp.secret.to_bytes())
        .expect("Failed to create secret key array");

    output.into_raw()
}
