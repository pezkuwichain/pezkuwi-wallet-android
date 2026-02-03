# PezWallet Android - Pezkuwi Uyumluluk Değişiklikleri

Bu dosya, Pezkuwi chain uyumluluğu için yapılan tüm değişiklikleri takip eder.
Context sıfırlanması durumunda referans olarak kullanılmalıdır.

---

## DEBUG KODLARI (Production öncesi KALDIRILMALI)

### 1. FeeLoaderV2Provider.kt - Hata mesajı gösterimi
**Dosya:** `feature-wallet-api/src/main/java/io/novafoundation/nova/feature_wallet_api/presentation/mixin/fee/v2/FeeLoaderV2Provider.kt`
**Değişiklik:**
```kotlin
// ÖNCE:
message = resourceManager.getString(R.string.choose_amount_error_fee),

// SONRA (DEBUG):
message = "DEBUG: $errorMsg | Runtime: $diagnostics",
```
**Temizleme:**
- `"DEBUG: $errorMsg | Runtime: $diagnostics"` → `resourceManager.getString(R.string.choose_amount_error_fee)` olarak geri al
- `val diagnostics = try { ... }` bloğunu kaldır

---

### 2. RuntimeFactory.kt - Diagnostic değişken ve log'lar
**Dosya:** `runtime/src/main/java/io/novafoundation/nova/runtime/multiNetwork/runtime/RuntimeFactory.kt`
**Eklenenler:**
```kotlin
// Companion object içinde:
companion object {
    @Volatile
    var lastDiagnostics: String = "not yet initialized"
}

// constructRuntimeInternal içinde:
lastDiagnostics = "typesUsage=$typesUsage, ExtrinsicSig=$hasExtrinsicSignature, MultiSig=$hasMultiSignature, typeCount=${types.size}"

// Log satırları:
Log.d("RuntimeFactory", "DEBUG: TypesUsage for chain $chainId = $typesUsage")
Log.d("RuntimeFactory", "DEBUG: Loading BASE types for $chainId")
Log.d("RuntimeFactory", "DEBUG: BASE types loaded, hash=$baseHash, typeCount=${types.size}")
Log.d("RuntimeFactory", "DEBUG: Chain $chainId - ExtrinsicSignature=$hasExtrinsicSignature, MultiSignature=$hasMultiSignature, typesUsage=$typesUsage, typeCount=${types.size}")
Log.d("RuntimeFactory", "DEBUG: BaseTypes loaded, length=${baseTypesRaw.length}, contains ExtrinsicSignature=${baseTypesRaw.contains("ExtrinsicSignature")}")
Log.e("RuntimeFactory", "DEBUG: BaseTypes NOT in cache!")
```
**Temizleme:**
- `companion object { ... }` bloğunu kaldır
- `lastDiagnostics = ...` satırını kaldır
- Tüm `Log.d/Log.e("RuntimeFactory", "DEBUG: ...")` satırlarını kaldır

---

### 3. CustomTransactionExtensions.kt - Log satırları
**Dosya:** `runtime/src/main/java/io/novafoundation/nova/runtime/extrinsic/CustomTransactionExtensions.kt`
**Temizlenecek:** Tüm `Log.d(TAG, ...)` satırları ve `private const val TAG` tanımı

---

### 4. ExtrinsicBuilderFactory.kt - Log satırları
**Dosya:** `runtime/src/main/java/io/novafoundation/nova/runtime/extrinsic/ExtrinsicBuilderFactory.kt`
**Temizlenecek:** Tüm `Log.d(TAG, ...)` satırları ve `private const val TAG` tanımı

---

### 5. PezkuwiAddressConstructor.kt - Log satırları
**Dosya:** `common/src/main/java/io/novafoundation/nova/common/utils/PezkuwiAddressConstructor.kt`
**Temizlenecek:** Tüm `Log.d(TAG, ...)` satırları ve `private const val TAG` tanımı

---

## FEATURE DEĞİŞİKLİKLERİ (Kalıcı)

### 1. PezkuwiAddressConstructor.kt - YENİ DOSYA
**Dosya:** `common/src/main/java/io/novafoundation/nova/common/utils/PezkuwiAddressConstructor.kt`
**Açıklama:** Pezkuwi chain'leri için özel address constructor. SDK'nın AddressInstanceConstructor'ı "Address" type'ını ararken, Pezkuwi "pezsp_runtime::multiaddress::MultiAddress" kullanıyor.

---

### 2. RuntimeSnapshotExt.kt - Address type lookup
**Dosya:** `runtime/src/main/java/io/novafoundation/nova/runtime/util/RuntimeSnapshotExt.kt`
**Değişiklik:** Birden fazla address type ismi deneniyor:
```kotlin
val addressType = typeRegistry["Address"]
    ?: typeRegistry["MultiAddress"]
    ?: typeRegistry["sp_runtime::multiaddress::MultiAddress"]
    ?: typeRegistry["pezsp_runtime::multiaddress::MultiAddress"]
    ?: return false
```

---

### 3. Signed Extension Dosyaları - YENİ DOSYALAR
**Dosyalar:**
- `runtime/src/main/java/io/novafoundation/nova/runtime/extrinsic/extensions/AuthorizeCall.kt`
- `runtime/src/main/java/io/novafoundation/nova/runtime/extrinsic/extensions/CheckNonZeroSender.kt`
- `runtime/src/main/java/io/novafoundation/nova/runtime/extrinsic/extensions/CheckWeight.kt`
- `runtime/src/main/java/io/novafoundation/nova/runtime/extrinsic/extensions/WeightReclaim.kt`
- `runtime/src/main/java/io/novafoundation/nova/runtime/extrinsic/extensions/PezkuwiCheckMortality.kt`

**Açıklama:** Pezkuwi chain'leri için özel signed extension'lar

---

### 3.1. PezkuwiCheckMortality.kt - YENİ DOSYA
**Dosya:** `runtime/src/main/java/io/novafoundation/nova/runtime/extrinsic/extensions/PezkuwiCheckMortality.kt`
**Açıklama:** SDK'nın CheckMortality'si metadata type lookup yaparak encode ediyor ve Pezkuwi'de bu başarısız oluyordu ("failed to encode extension CheckMortality" hatası). Bu custom extension, Era ve blockHash'i doğrudan encode ediyor.
**Neden gerekli:** SDK CheckMortality, Era type'ını metadata'dan arıyor. Pezkuwi metadata'sında Era type'ı `pezsp_runtime.generic.era.Era` DictEnum olarak tanımlı ve SDK bunu handle edemiyor.

**Kod:**
```kotlin
class PezkuwiCheckMortality(
    era: Era.Mortal,
    blockHash: ByteArray
) : FixedValueTransactionExtension(
    name = "CheckMortality",
    implicit = blockHash,  // blockHash goes into signer payload
    explicit = createEraEntry(era)  // Era as DictEnum.Entry
)
```

---

### 4. CustomTransactionExtensions.kt - Pezkuwi extension logic
**Dosya:** `runtime/src/main/java/io/novafoundation/nova/runtime/extrinsic/CustomTransactionExtensions.kt`
**Değişiklik:** `isPezkuwiChain()` fonksiyonu eklendi, Pezkuwi için farklı extension'lar kullanılıyor

---

### 5. Address encoding yaklaşımı değişikliği
**Eski yaklaşım:** `AddressInstanceConstructor` veya `PezkuwiAddressConstructor` ile type ismine göre tahmin
**Yeni yaklaşım:** `argumentType("dest").constructAccountLookupInstance(accountId)` ile metadata'dan gerçek type alınıyor

**Güncellenen dosyalar:**
1. `feature-wallet-api/.../ExtrinsicBuilderExt.kt` - **YENİ YAKLAŞIM**: metadata'dan type alıyor
2. `feature-governance-impl/.../ExtrinsicBuilderExt.kt` - Zaten doğru yaklaşımı kullanıyordu
3. Diğer dosyalar hala PezkuwiAddressConstructor kullanıyor (gerekirse güncellenecek):
   - `feature-staking-impl/.../ExtrinsicBuilderExt.kt`
   - `feature-staking-impl/.../NominationPoolsCalls.kt`
   - `feature-proxy-api/.../ExtrinsicBuilderExt.kt`
   - `feature-wallet-impl/.../StatemineAssetTransfers.kt`
   - `feature-wallet-impl/.../OrmlAssetTransfers.kt`
   - `feature-wallet-impl/.../NativeAssetIssuer.kt`
   - `feature-wallet-impl/.../OrmlAssetIssuer.kt`
   - `feature-wallet-impl/.../StatemineAssetIssuer.kt`
   - `feature-account-impl/.../ProxiedSigner.kt`

---

### 6. chains.json - feeViaRuntimeCall ve types
**Dosya:** `pezkuwi-config/chains.json`
**Değişiklik:**
- Pezkuwi chain'lerine `"feeViaRuntimeCall": true` eklendi
- `"types": { "overridesCommon": false }` eklendi (base types yüklenmesi için - ExtrinsicSignature type hatası düzeltmek için)
**Etkilenen chain'ler:**
- Pezkuwi Mainnet (bb4a61ab0c4b8c12f5eab71d0c86c482e03a275ecdafee678dea712474d33d75)
- Pezkuwi Asset Hub (00d0e1d0581c3cd5c5768652d52f4520184018b44f56a2ae1e0dc9d65c00c948)
- Pezkuwi People Chain (58269e9c184f721e0309332d90cafc410df1519a5dc27a5fd9b3bf5fd2d129f8)
- Zagros Testnet (96eb58af1bb7288115b5e4ff1590422533e749293f231974536dc6672417d06f)

---

### 7. pezkuwi.json - Chain-specific types (ASSETS)
**Dosya:** `runtime/src/main/assets/types/pezkuwi.json`
**Açıklama:** Pezkuwi chain'leri için özel type tanımları
```json
{
  "types": {
    "ExtrinsicSignature": "MultiSignature",
    "Address": "pezsp_runtime::multiaddress::MultiAddress",
    "LookupSource": "pezsp_runtime::multiaddress::MultiAddress"
  },
  "typesAlias": {
    "pezsp_runtime::multiaddress::MultiAddress": "MultiAddress",
    "pezsp_runtime::MultiSignature": "MultiSignature",
    "pezsp_runtime.generic.era.Era": "Era"
  }
}
```
**NOT:** Bu dosya şu anda kullanılmıyor çünkü TypesUsage.BASE kullanılıyor. TypesUsage.BOTH veya OWN için chains.json'da URL eklenebilir.

---

## SORUN GEÇMİŞİ

1. **"Network not responding"** - Fee hesaplama hatası
   - Çözüm: feeViaRuntimeCall eklendi, custom signed extension'lar eklendi

2. **"IllegalStateException: Type Address was not found"** - Address type lookup hatası
   - Çözüm: RuntimeSnapshotExt.kt'de birden fazla type ismi deneniyor

3. **"EncodeDecodeException: is not a valid instance"** - Address encoding hatası
   - Çözüm: `argumentType("dest").constructAccountLookupInstance(accountId)` ile metadata'dan gerçek type alınıyor (ExtrinsicBuilderExt.kt)

4. **"failed to encode extension CheckMortality"** - CheckMortality encoding hatası
   - SDK'nın CheckMortality'si metadata type lookup yaparak Era'yı encode etmeye çalışıyor
   - Pezkuwi Era type'ı `pezsp_runtime.generic.era.Era` DictEnum olarak tanımlı
   - Çözüm: `PezkuwiCheckMortality` custom extension'ı oluşturuldu, Era'yı `DictEnum.Entry("MortalX", secondByte)` olarak veriyor

5. **"IllegalStateException: Type ExtrinsicSignature was not found"** - ExtrinsicSignature type hatası (DEVAM EDİYOR)
   - SDK "ExtrinsicSignature" type'ını arıyor ama Pezkuwi chain'leri `"types": null` kullanıyordu
   - `TypesUsage.NONE` olduğu için base types (default.json) yüklenmiyordu
   - Denenen çözüm: chains.json'da `"types": { "overridesCommon": false }` eklendi → `TypesUsage.BASE` kullanılıyor
   - Base types URL: `https://raw.githubusercontent.com/pezkuwichain/pezkuwi-wallet-utils/master/chains/types/default.json`
   - default.json'da `"ExtrinsicSignature": "MultiSignature"` tanımlı
   - Cache temizlenip uninstall/reinstall yapıldı ama hata devam ediyor
   - Debug diagnostics eklendi - test sonucunu bekliyoruz

---

## ÇALIŞAN İMPLEMENTASYONLAR (Referans)

### 1. pezkuwi-extension (Browser Extension)
**Konum:** `/home/mamostehp/pezkuwi-extension/`
**Nasıl çalışıyor:**
- `@pezkuwi/types` (polkadot.js fork) kullanıyor
- `TypeRegistry` ile dynamic type handling
- Custom user extensions:
```javascript
const PEZKUWI_USER_EXTENSIONS = {
  AuthorizeCall: {
    extrinsic: {},
    payload: {}
  }
};
```
- `registry.setSignedExtensions(payload.signedExtensions, PEZKUWI_USER_EXTENSIONS)` ile extension'lar ekleniyor
- Metadata'dan registry oluşturuluyor: `metadataExpand(metadata, false)`

### 2. pezkuwi-subxt (Rust)
**Konum:** `/home/mamostehp/pezkuwi-sdk/vendor/pezkuwi-subxt/`
**Nasıl çalışıyor:**
- Rust'ta compile-time type generation
- Metadata'dan otomatik type oluşturma

### 3. Telegram Miniapp
- Web tabanlı, polkadot.js kullanıyor
- `"types": null` ile çalışıyor çünkü metadata v14+ self-contained

---

## TEMİZLEME KONTROL LİSTESİ

Production release öncesi yapılacaklar:

- [ ] FeeLoaderV2Provider.kt - DEBUG mesajını ve diagnostics'i kaldır
- [ ] RuntimeFactory.kt - companion object ve debug log'ları kaldır
- [ ] CustomTransactionExtensions.kt - Log satırlarını kaldır
- [ ] ExtrinsicBuilderFactory.kt - Log satırlarını kaldır
- [ ] PezkuwiAddressConstructor.kt - Log satırlarını kaldır (varsa)
- [ ] Test et: Transfer işlemi çalışıyor mu?
- [ ] Test et: Fee hesaplama çalışıyor mu?

---

## TYPE LOADING AKIŞI (Referans)

```
chains.json
    ↓
"types": { "overridesCommon": false }  →  TypesUsage.BASE
"types": { "url": "...", "overridesCommon": false }  →  TypesUsage.BOTH
"types": { "url": "...", "overridesCommon": true }  →  TypesUsage.OWN
"types": null  →  TypesUsage.NONE
    ↓
RuntimeFactory.constructRuntime()
    ↓
TypesUsage.BASE → constructBaseTypes() → fetch from DEFAULT_TYPES_URL
TypesUsage.BOTH → constructBaseTypes() + constructOwnTypes()
TypesUsage.OWN → constructOwnTypes() only
TypesUsage.NONE → use v14Preset() only
    ↓
TypeRegistry
    ↓
RuntimeSnapshot
```

**DEFAULT_TYPES_URL:** `https://raw.githubusercontent.com/pezkuwichain/pezkuwi-wallet-utils/master/chains/types/default.json`

---

*Son güncelleme: 2026-02-03 (Debug diagnostics eklendi, extension analizi yapıldı)*
