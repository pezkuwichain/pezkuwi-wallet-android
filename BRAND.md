# Pezkuwi Wallet — Brand Rules

This wallet must stay aligned with the **Pezkuwi brand book**. The canonical,
ecosystem-wide source of truth lives on the wiki:
**https://wiki.pezkuwichain.io/brand/**. This file is the wallet-specific,
enforceable summary — read it before changing any colors, fonts, or imagery.

## The one hard rule

> **Only the Kurdistan palette (kesk green, sor red, zer gold, fire orange) plus
> navy/neutral are allowed. Pink, magenta and purple are FORBIDDEN — anywhere.**

This applies to XML colors, Compose `Color(0x…)` literals, vector drawables,
gradients, **and raster images** (PNG/WebP banners and illustrations).

## Palette (source of truth)

| Token | Hex | | Token | Hex |
|-------|-----|-|-------|-----|
| kesk (primary) | `#009639` | | base bg | `#05081C` |
| kesk-700 | `#017A2F` | | screen bg | `#08090E` |
| positive | `#2FC864` | | elevated | `#181920` |
| sor (red) | `#E2231A` | | nav | `#0F111A` |
| negative | `#E53450` | | text primary | `#E0FFFFFF` |
| zer (gold) | `#FDB813` | | text secondary | `#7AFFFFFF` |
| warning | `#EBC50A` | | text tertiary | `#52FFFFFF` |
| fire (orange) | `#FF7A00` | | periwinkle tint | `#999EC7` @ 10/16/24% |
| info | `#2AB0F2` | | | |

## Typography

- **Space Grotesk** — display: balances, screen titles, large numbers
- **Plus Jakarta Sans** — body / UI text
- **JetBrains Mono** — addresses, hashes, block numbers

All three are OFL-licensed and cover Latin-ext / Turkish / Kurdish Kurmancî
glyphs. Fonts live in `common/src/main/res/font/`; the theme maps them via
`fontRegular/SemiBold/Bold/ExtraBold/ExtraLight` in `common/.../values/themes.xml`.

## Brand mark & imagery

- The brand mark is the **Newroz flame** (`nevroz-fire-*`). Do **not** use the
  retired Roj-rosette mark or a Kurdistan-map logo anywhere.
- First-launch splash uses the Newroz flame (`ic_loading_screen_logo`).
- Onboarding hero uses the **Global United States of Pezkuwi** infographic.
- Pull chain/token/dapp icons from `pezkuwichain/pezkuwi-wallet-utils`, don't
  invent art. Keep one outline icon set (2px stroke, round caps).

## Where colors are defined

- Named colors: `common/src/main/res/values/colors.xml`
- Theme/type: `common/src/main/res/values/themes.xml`, `styles.xml`

## PR checklist (brand)

- [ ] No magenta/purple/pink in `colors.xml` (`#BD387F`, `#661D78`, `#443679`, `#FF48A5`, …).
- [ ] No `purple`/`magenta`/`pink`-named drawables or Compose color literals.
- [ ] Raster assets scanned for pink/magenta pixels (see snippet below); none above noise.
- [ ] New text uses the brand font roles; addresses use JetBrains Mono.
- [ ] Any new mark is the Newroz flame, not a map/rosette.

Raster pink scan (run from repo root):

```python
# python3 with Pillow
from PIL import Image; import glob
def pink(r,g,b): return r>150 and g<110 and b>120 and (r-g)>70
for f in glob.glob('**/res/drawable*/*.png',recursive=True)+glob.glob('**/res/drawable*/*.webp',recursive=True):
    if '/build/' in f: continue
    im=Image.open(f).convert('RGBA'); im.thumbnail((96,96)); px=im.load(); w,h=im.size; c=t=0
    for y in range(h):
        for x in range(w):
            r,g,b,a=px[x,y]
            if a<30: continue
            t+=1; c+=pink(r,g,b)
    if t and c/t>0.02: print(round(c/t*100,1),'%',f)
```

> Exception: third-party trademark logos (e.g. `ic_powered_by_oak`, OAK Network)
> must **not** be recolored — leave them as the owner ships them.

## Licensing note (important)

This app is a fork of **Nova Wallet**, licensed under **Apache-2.0**. Apache-2.0
lets us fork, rebrand and ship — **but you must KEEP `LICENSE` and `NOTICE`** and
their attributions, and state changes. Do **not** delete the attribution (that
would breach the license). Removing Nova's *trademark* (name/logo) from the UI is
required; preserving the *copyright notice* is also required. The package name
`io.novafoundation.nova.app` stays (Play Store identity; not a trademark issue).
