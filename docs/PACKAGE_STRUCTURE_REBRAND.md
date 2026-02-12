# Package Structure Rebrand Guide

**Tarih:** 2026-01-23
**Durum:** BEKLEMEDE - Büyük değişiklik, dikkatli planlama gerektirir

---

## Mevcut Durum

| Öğe | Sayı |
|-----|------|
| `io.novafoundation` package referansları | ~49,041 |
| Etkilenen Kotlin/Java dosyaları | ~2,000+ |
| Module sayısı | 65+ |

---

## Hedef Dönüşüm

```
io.novafoundation.nova  →  io.pezkuwichain.wallet
```

### Örnekler:

| Mevcut | Hedef |
|--------|-------|
| `io.novafoundation.nova.app` | `io.pezkuwichain.wallet.app` |
| `io.novafoundation.nova.common` | `io.pezkuwichain.wallet.common` |
| `io.novafoundation.nova.feature_wallet_api` | `io.pezkuwichain.wallet.feature_wallet_api` |
| `io.novafoundation.nova.runtime` | `io.pezkuwichain.wallet.runtime` |

---

## Değişiklik Kapsamı

### 1. Dizin Yapısı Değişikliği

Her modülde:
```
src/main/java/io/novafoundation/nova/
    ↓
src/main/java/io/pezkuwichain/wallet/
```

### 2. Package Declaration Değişikliği

Her Kotlin/Java dosyasının ilk satırı:
```kotlin
// ÖNCE:
package io.novafoundation.nova.feature_wallet_api.domain

// SONRA:
package io.pezkuwichain.wallet.feature_wallet_api.domain
```

### 3. Import Statement Değişikliği

```kotlin
// ÖNCE:
import io.novafoundation.nova.common.utils.Event
import io.novafoundation.nova.feature_account_api.domain.model.Account

// SONRA:
import io.pezkuwichain.wallet.common.utils.Event
import io.pezkuwichain.wallet.feature_account_api.domain.model.Account
```

---

## Otomatik Rebrand Script

```bash
#!/bin/bash
# package_rebrand.sh
# DIKKAT: Bu script'i çalıştırmadan önce backup alın!

WALLET_DIR="/home/mamostehp/pezWallet/pezkuwi-wallet-android"
OLD_PACKAGE="io.novafoundation.nova"
NEW_PACKAGE="io.pezkuwichain.wallet"
OLD_PATH="io/novafoundation/nova"
NEW_PATH="io/pezkuwichain/wallet"

# 1. Dizin yapısını değiştir
find "$WALLET_DIR" -type d -path "*/$OLD_PATH" | while read dir; do
    new_dir=$(echo "$dir" | sed "s|$OLD_PATH|$NEW_PATH|g")
    mkdir -p "$(dirname "$new_dir")"
    mv "$dir" "$new_dir"
done

# 2. Package declarations ve imports değiştir
find "$WALLET_DIR" -type f \( -name "*.kt" -o -name "*.java" \) | while read file; do
    sed -i "s|$OLD_PACKAGE|$NEW_PACKAGE|g" "$file"
done

# 3. build.gradle namespace'lerini kontrol et (zaten yapıldı)
# grep -rn "namespace" --include="*.gradle" "$WALLET_DIR"

echo "Rebrand tamamlandı. Build test edin."
```

---

## Riskler ve Dikkat Edilmesi Gerekenler

### 1. Android Resource ID'leri
- `R.drawable.*`, `R.string.*` gibi resource referansları etkilenmez
- Ama `BuildConfig` referansları güncellenebilir

### 2. Dagger/Hilt Dependency Injection
- Component, Module, Scope annotation'ları
- Generated kod yeniden oluşturulmalı (clean build)

### 3. Room Database
- Entity, DAO sınıfları
- Migration'lar kontrol edilmeli

### 4. ProGuard/R8
- `proguard-rules.pro` dosyalarındaki referanslar

### 5. AndroidManifest.xml
- Activity, Service, Provider tanımları
- Intent filter'lar

### 6. Test Dosyaları
- `androidTest` ve `test` klasörlerindeki dosyalar da değişmeli

---

## Önerilen Yaklaşım

### Faz 1: Hazırlık (1-2 gün)
1. [ ] Mevcut durumun tam backup'ı
2. [ ] Tüm testlerin geçtiğini doğrula
3. [ ] CI/CD pipeline'ı geçici olarak durdur

### Faz 2: Otomatik Dönüşüm (2-4 saat)
1. [ ] Script'i çalıştır
2. [ ] Build hatalarını kontrol et
3. [ ] IDE'de proje yapısını yenile (Invalidate Caches)

### Faz 3: Manuel Düzeltmeler (1-2 gün)
1. [ ] Build hatalarını düzelt
2. [ ] Dagger/Hilt generated kod sorunları
3. [ ] ProGuard kuralları güncelle

### Faz 4: Test (1 gün)
1. [ ] Unit test'leri çalıştır
2. [ ] Integration test'leri çalıştır
3. [ ] Manual UI testing
4. [ ] APK build ve install test

### Faz 5: Finalize
1. [ ] Commit ve push
2. [ ] CI/CD'yi yeniden aktif et
3. [ ] Release build test

---

## Alternatif: Kademeli Rebrand

Eğer tek seferde yapmak riskli görünüyorsa:

1. **Modül bazlı değişiklik** - Her modülü ayrı ayrı rebrand et
2. **Alias kullanımı** - Geçiş döneminde typealias ile uyumluluk
3. **Git branch** - Ayrı bir branch'te çalış, test et, merge et

---

## Zaten Tamamlanan İşler

✅ Gradle namespace'ler: `io.novafoundation.nova.*` → `io.pezkuwichain.wallet.*`
✅ Display name'ler: "Nova Wallet" → "Pezkuwi Wallet"
✅ Deep link scheme: `novawallet://` → `pezkuwiwallet://`
✅ JavaScript interface: `Nova_*` → `Pezkuwi_*`
✅ Backup dosya adları: `novawallet_backup.json` → `pezkuwiwallet_backup.json`
✅ User-Agent: "Nova Wallet (Android)" → "Pezkuwi Wallet (Android)"
✅ Nevroz fire branding asset'leri

---

## Sonuç

Bu değişiklik büyük ve riskli. Yapılması tavsiye edilir ama dikkatli planlama ile:

1. **Şu an için:** Mevcut durum çalışır durumda, build alınabilir
2. **Kısa vadede:** Package structure değişikliği planlanmalı
3. **Uzun vadede:** Tamamen `io.pezkuwichain.wallet` kullanılmalı

**Öneri:** Önce mevcut haliyle release build alıp test edin. Ardından bu değişikliği ayrı bir sprint'te planlayın.
