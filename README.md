# Pezkuwi Wallet Android

Next generation mobile wallet for Pezkuwichain and the Polkadot ecosystem.

[![](https://img.shields.io/twitter/follow/pezkuwichain?label=Follow&style=social)](https://twitter.com/pezkuwichain)

## About

Pezkuwi Wallet is a next-generation mobile application for the Pezkuwichain and Polkadot ecosystem. It provides a transparent, community-oriented wallet experience with convenient UX/UI, fast performance, and strong security.

**Key Features:**
- Full Pezkuwichain support (HEZ & PEZ tokens)
- Full Polkadot ecosystem compatibility
- Staking, Governance, DeFi
- NFT support
- Cross-chain transfers (XCM)
- Hardware wallet support (Ledger, Polkadot Vault)
- WalletConnect v2
- Push notifications

## Native Tokens

| Token | Network | Description |
|-------|---------|-------------|
| HEZ | Relay Chain | Native token for fees and staking |
| PEZ | Asset Hub | Governance token |

## Build Instructions

### Clone Repository

```bash
git clone git@github.com:pezkuwichain/pezkuwi-wallet-android.git
```

### Install NDK

Install NDK version `26.1.10909125` from SDK Manager:
Tools -> SDK Manager -> SDK Tools -> NDK (Side by Side)

### Install Rust

Install Rust by following [official instructions](https://www.rust-lang.org/tools/install).

Add Android build targets:

```bash
rustup target add armv7-linux-androideabi
rustup target add i686-linux-android
rustup target add x86_64-linux-android
rustup target add aarch64-linux-android
```

### Update local.properties

Add the following lines to your `local.properties`:

```properties
ACALA_PROD_AUTH_TOKEN=mock
ACALA_TEST_AUTH_TOKEN=mock
CI_KEYSTORE_KEY_ALIAS=mock
CI_KEYSTORE_KEY_PASS=mock
CI_KEYSTORE_PASS=mock
DEBUG_GOOGLE_OAUTH_ID=mock
RELEASE_GOOGLE_OAUTH_ID=mock
DWELLIR_API_KEY=mock
EHTERSCAN_API_KEY_ETHEREUM=mock
EHTERSCAN_API_KEY_MOONBEAM=mock
EHTERSCAN_API_KEY_MOONRIVER=mock
INFURA_API_KEY=mock
MERCURYO_PRODUCTION_SECRET=mock
MERCURYO_TEST_SECRET=mock
MOONBEAM_PROD_AUTH_TOKEN=mock
MOONBEAM_TEST_AUTH_TOKEN=mock
MOONPAY_PRODUCTION_SECRET=mock
MOONPAY_TEST_SECRET=mock
WALLET_CONNECT_PROJECT_ID=mock
```

**Note:** Firebase and Google-related features (Notifications, Cloud Backups) require proper configuration.

### Build Types

- `debug`: Uses fixed keystore for Google services
- `debugLocal`: Uses your local debug keystore
- `release`: Production build

## Supported Languages

- English
- Turkish (Türkçe)
- Kurdish Kurmanji (Kurmancî)
- Spanish (Español)
- French (Français)
- German (Deutsch)
- Russian (Русский)
- Japanese (日本語)
- Chinese (中文)
- Korean (한국어)
- Portuguese (Português)
- Vietnamese (Tiếng Việt)
- And more...

## Resources

- Website: https://pezkuwichain.io
- Documentation: https://docs.pezkuwichain.io
- Telegram: https://t.me/pezkuwichain
- Twitter: https://twitter.com/pezkuwichain
- GitHub: https://github.com/pezkuwichain

## License

Pezkuwi Wallet Android is available under the Apache 2.0 license. See the LICENSE file for more info.

Based on Nova Wallet (https://novawallet.io) - © Novasama Technologies GmbH

© Dijital Kurdistan Tech Institute 2026
