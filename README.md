# Echoliminal

> Mod Fabric Minecraft 1.21.11 yang dibikin karena gabut. Isinya level liminal dari model Blender (bukan blok), dunia endless, dan link live dua arah: Blender ↔ Minecraft, realtime.

Ini **bukan project serius**. Ga ada roadmap, ga ada deadline, ga ada janji update. Awalnya cuma "eh kalau level Minecraft-nya model 3D dari Blender gimana ya", terus keterusan, terus tiba-tiba Blender sama Minecraft-nya bisa ngobrol. Dikerjain bareng **Claude Opus 5.5** (Claude Code): dia yang banyak ngoding, ngetes, dan nemu bug-bugnya sendiri; saya yang bilang "eh tambahin ini dong" tiap lima menit.

Hasilnya ternyata lumayan menarik, jadi disimpen di sini.

---

## Isinya apa

### 1. Level liminal dari `.glb`
Dimensi `echoliminal:liminal` isinya **mesh**, bukan voxel. Satu level = satu file `.glb` dari Blender (1 m = 1 blok).
- Bisa jalan di atas mesh-nya (collision per segitiga, tangga dan ramp kerasa).
- Bisa **dihancurin per sel** kayak blok, potongan geometrinya bisa diambil terus dipasang lagi.
- Lighting di-bake sendiri (ray tracing kecil di CPU): panel lampu, matahari, bayangan, satu kali pantulan.
- Level bawaan: `hallway` (backrooms prosedural), `map79` (gurun merah ala DOOM), `room` (scan fotogrametri ruangan).

### 2. Dunia endless
`echoliminal:liminal_endless`: lorong-lorong liminal yang digenerate terus tanpa ujung.

### 3. Blender Live (yang paling gabut)
Scene Blender yang lagi dibuka **jadi level di game, realtime**. Geser objek di Blender, di game langsung pindah, sekitar 0,02–0,13 detik plus bake lighting sekitar setengah detik. Dan balik arah juga:

| Blender → Minecraft | Minecraft → Blender |
|---|---|
| objek mesh (modifier ikut) | player + mob + boat, **beranimasi** sinkron |
| material, tekstur, lampu emisif | blok yang dipasang, pakai **model aslinya** (grass ke-tint, tangga, obor, dll.) |
| light Point/Spot/Area → cahaya beneran, **warnanya ikut** | item drop, TNT (ngembang terus meledak), falling block |
| World → langit (warna polos atau HDRI kecil) | item yang dipegang |
| posisi kamera viewport → muncul di game sebagai observer nyala | sign + teksnya |
| | blok yang dihancurin → ikut bolong di Blender |
| | blok bercahaya → point light di Blender |

Detail lengkap + protokolnya: [`docs/BLENDER-LIVE.md`](docs/BLENDER-LIVE.md).

---

## Cara jalanin

Butuh **Java 21** dan (kalau mau Blender Live) **Blender 5.2**.

```bat
gradlew.bat runLiminalClient
```

Masuk world mana aja, terus `/echoliminal enter hallway`.
Biar langsung masuk world tertentu: `gradlew.bat runLiminalClient -PquickPlay=<nama folder world>`.

> Di PC saya repo ini tinggal di dalam proyek Gradle yang lebih gede (`D:\JAVAMODS`, bareng mod lain) biar cache Minecraft-nya ga dobel. Kalau di-clone sendirian, `settings.gradle` + `gradle.properties` di sini bikin dia bisa di-build mandiri. `gradlew.bat` otomatis numpang cache induknya kalau ada.

### Blender Live
1. Copy (atau symlink) folder `tools/blender/echoliminal_live` ke `%APPDATA%\Blender Foundation\Blender\5.2\scripts\addons\`, terus aktifin di Preferences → Add-ons.
2. Jalanin game, buka world.
3. Di Blender: sidebar 3D View (N) → tab **Echoliminal** → isi nama level → **Connect** → **Put player in level**.
4. Edit apa aja. Liat game. Senyum sendiri.

Abis update mod/add-on: **restart Blender** (add-on cuma dimuat pas start). Panel nampilin versinya, dan game bakal komplain kalau add-on-nya ketinggalan.

---

## Struktur

```
src/main/java/dev/echoliminal/
  liminal/   level .glb: loader, sel, collision, lighting bake (light/)
  endless/   generator dunia endless
  live/      Blender Live: LiveLink (socket), LiveScene (level dari Blender)
  mixin/
src/client/java/dev/echoliminal/
  liminal/client/  renderer mesh, baker, cahaya
  endless/client/
  client/          LiveLinkClient (model blok, entity, item ke Blender)
tools/
  blender/echoliminal_live/  add-on Blender
  liminal/                   script Blender buat bikin level bawaan (+ .blend)
  endless/
docs/
  CUSTOM-WORLD.md   cara bikin level sendiri
  BLENDER-LIVE.md   Blender Live, lengkap
```

### Dev tools (Gradle)
| Task | Buat apa |
|---|---|
| `glbCheck -Pglb=level.glb` | cek isi `.glb` + jalan dari spawn |
| `cutCheck` | hitung cut face level |
| `endlessExport` | export dunia endless ke `.glb` buat dibuka di Blender |
| `liveSceneCheck -Pdir=... -Pref=...` | bandingin level hasil Blender Live sama `.glb` referensi |
| `runLiminalServer` | server headless buat ngetes link tanpa buka game |

---

## Yang perlu diketahui (alias "ini bukan bug, ini fitur")

- Blender Live cuma nyala di dev (`runLiminalClient`), cuma **singleplayer**, cuma `127.0.0.1`.
- Level dari Blender selalu kosong di dalam (ga ada batu pengisi); yang dihancurin ya bolong aja.
- Warna lampu cuma ngaruh ke mesh level. Entity sama blok vanilla tetep pake lightmap vanilla (agak kekuningan).
- Posisi item di tangan itu perkiraan, bukan transform persis tiap item.
- Font sign di Blender itu font default Blender, bukan font Minecraft.
- Blok animasi (sea lantern dkk) di Blender cuma frame pertama.
- Aset di `tools/liminal/map79_textures` dan `room_textures` dari download/scan pihak ketiga. Repo ini privat, jangan disebar.

---

## Kredit

- Saya: ide-ide random, ngetes, komplain.
- **Claude Opus 5.5** via Claude Code: hampir semua kodenya, termasuk ngetes sendiri pakai Blender headless + screenshot game, dan nemu bug yang ga pernah saya laporin.
- Fabric, Fabric API, Sodium, Mod Menu, Blender.

Lisensi: belum ada (All Rights Reserved). Namanya juga project gabut.
