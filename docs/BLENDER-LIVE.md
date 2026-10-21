# Blender Live: dunia Blender dan Minecraft yang menyatu

Scene Blender yang sedang kamu edit menjadi level liminal di game secara realtime. Hasil di game juga kembali ke Blender: pemain, blok yang dipasang, potongan mesh, dan lubang yang digali.

Link hanya aktif di dev (`runLiminalClient`). Di luar dev, aktifkan dengan `-Decholiminal.live=true`. Port default `47660` (`-Decholiminal.live.port`), dan hanya menerima koneksi dari `127.0.0.1`.

## Mulai

1. `gradlew.bat runLiminalClient`, lalu buka world apa saja. Dengan `-PquickPlay=<folder world>`, world langsung terbuka.
2. Di Blender 5.2, buka sidebar 3D View (N), tab **Echoliminal**. Add-on sudah terpasang: `%APPDATA%\Blender Foundation\Blender\5.2\scripts\addons\echoliminal_live` adalah junction ke `tools/blender/echoliminal_live`, jadi mengedit file di repo langsung berlaku setelah restart Blender.
3. Isi **Level** (huruf kecil, angka, `_` atau `-`), lalu klik **Connect**.
4. Klik **Put player in level**. Pemain di-teleport ke empty `spawn`.

Kode add-on hanya dimuat saat Blender start. Setelah mod atau add-on diperbarui, **restart Blender**. Panel menampilkan versi add-on, dan game memberi peringatan kalau add-on yang terhubung sudah ketinggalan.

Selama Blender terhubung, game tidak auto-pause saat kehilangan fokus. Kalau pause, dunia berhenti dan tidak ada yang tercermin. Setelan kembali seperti semula saat link putus.

## Blender ke game

- Setiap objek mesh, curve, teks, atau metaball yang terlihat dan ikut render dikirim **setelah modifier** (Boolean, Array, dll.). Hanya objek yang berubah yang dikirim ulang (dibandingkan lewat hash).
- Game hanya membangun ulang sel di area objek yang berubah. Lighting di-bake ulang di belakang layar, dan versi lama tetap tampil sampai yang baru siap, jadi tidak ada kedip. Pada hallway, satu edit memakan sekitar 0,02–0,13 detik sampai game menerimanya, ditambah bake 0,4–0,6 detik.
- Aturan sama dengan file `.glb`: 1 m = 1 blok, Z ke atas, material `_sky` / `_nocollide` / `_thin`, material berisi "light" atau ber-emission menyala, empty `spawn` dan `sun`, dan custom property scene (`sky_color`, `fog_*`, `time_of_day`, `stone_fill`, `unlit`, `day_cycle`, ...).
- Koleksi `AIR_cutters`, objek atau koleksi dengan *Disable in Renders*, dan semua koleksi `MC_*` tidak dikirim.
- **Drop outside**: untuk balok padat yang dilubangi dari dalam (seperti `level_shell`), sisi luarnya tidak pernah terlihat. Klik tombol di panel (custom property `drop_outside`) supaya sisi itu tidak dikirim.
- **Draft lighting**: mengirim `unlit` sementara, sehingga game melewati bake. Cocok saat banyak menggeser; matikan untuk melihat hasil asli.
- **Level live selalu kosong di dalam:** tidak ada batu pengisi dan tidak ada dinding batu (cut faces) di sel yang dibobol, apa pun nilai `stone_fill` di scene. Sel yang dirusak di game menjadi lubang polos.
- **Lampu:** light Point, Spot, dan Area di Blender menjadi blok `minecraft:light` sungguhan di game. Lampu ini menerangi pemain, mob, dan blok, dan ikut di-bake ke mesh. Levelnya dihitung dari power (1000 W = 15, 250 W = 8; rumus 15·√(W/1000)), atau dari custom property `mc_level` (1–15) pada objek light. Light **Sun** menjadi arah matahari level, kecuali sudah ada empty `sun`. Warna lampu tidak ikut, karena cahaya blok Minecraft selalu putih.
- Level live disimpan di `run/config/echoliminal/live/<level>/` dan tetap ada walaupun Blender tidak terbuka. Ia juga muncul di `/echoliminal enter`.

## Langit dan kamera

- **World Blender menjadi langit game.** Warna polos (termasuk abu-abu default Blender) dipakai sebagai `sky_color` dan `fog_color`. Environment Texture (HDRI) diperkecil ke 256×128 dan dikirim sebagai skybox; warna rata-ratanya menjadi warna langit dan kabut. Custom property `sky_color`/`fog_color` di scene tetap diutamakan, jadi hapus property itu kalau ingin mengikuti World.
- **Game bisa melihat kamera Blender.** Viewport 3D pertama dikirim 10 kali per detik dan tampil di game sebagai observer menyala oranye berlabel "Blender" yang bergerak halus. Matikan dengan **Show camera in game**. Saat kamu melihat lewat mata pemain (**Eyes**), penandanya tidak dikirim.

## Game ke Blender (koleksi `MC_LIVE`, tidak pernah dikirim balik)

| Objek | Isi |
|---|---|
| `MC_Things` | item drop (berputar dan naik-turun), TNT yang menyala (membesar sebelum meledak) dan falling block |
| `MC_Held` | item di tangan pemain dan mob (blok sebagai blok kecil, item lain sebagai teksturnya) |
| `MC_Effects` | ledakan TNT: bola api dan kilatan cahaya selama 0,6 detik |
| `MC_Entities` | pemain, mob, dan boat/raft di level: model dan skin asli, beranimasi sinkron dengan game (vertex yang dipose game setiap tick, termasuk jalan, kepala, dan tangan) |
| `MC_Eye` | kamera di mata pemain (FOV 70). Tombol **Eyes** menjadikannya kamera scene, sehingga kamu melihat persis apa yang dilihat pemain. |
| `MC_Blocks` | blok yang dipasang di level dengan **model aslinya**: baked quads dari model JSON blok (termasuk resource pack), setiap sisi dengan teksturnya sendiri. Grass, daun, dan sejenisnya di-tint warna biome (tint dibakar ke salinan teksturnya, jadi terlihat di semua mode tampilan). Tangga, slab, obor, dan tanaman berbentuk sesuai modelnya. Blok bercahaya (obor, glowstone, lentera) juga mendapat point light `mcl_x_y_z`, dengan level 15 = 1000 W. Bloknya sendiri tidak memberi bayangan, supaya cahaya lampu di dalamnya bisa keluar. |
| `MC_Pieces` | potongan mesh liminal yang dipasang kembali, berupa segitiga aslinya |
| `MC_Cutters` | pemotong lubang (tersembunyi), satu per objek yang pernah kena |

**Sign** tampil dengan papan dari kayunya sendiri dan teks aslinya (depan dan belakang, warna dye, glow ink), lalu diperbarui setiap kali diedit di game. Blok animasi seperti sea lantern memakai frame pertama teksturnya.

Semua objek cermin ikut ter-render (F12), kecuali pemotong lubang. Kalau kamu menduplikasi objek cermin (Shift+D pada blok, potongan, atau mob), salinannya otomatis keluar dari `MC_*` dan menjadi objek level yang dikirim ke game.

**Mesh yang dihancurkan di game ikut hancur di Blender.** Begitu sel dirusak di game, setiap objek yang saat itu punya geometri di sel tersebut mendapat modifier Geometry Nodes `MC holes`. Modifier ini memotong sel itu dan menghapus dinding sisa Boolean, jadi lubangnya tetap terbuka seperti di game. Pemotongnya disimpan dalam koordinat lokal objek, jadi lubang ikut berpindah kalau objek digeser. Setelah geometri yang sudah bolong terkirim, game menghapus tanda "broken" untuk sel itu.

Akibatnya, **objek baru yang kamu taruh di bekas lubang tidak terpotong** dan langsung muncul di game. Tombol **Restore holes** mengembalikan semua lubang dengan menghapus modifier dan pemotongnya. Sisa dari versi lama (modifier Boolean `MC holes` dan wireframe `MC_Holes`) dihapus otomatis saat connect.

Lubang hanya tercatat kalau sel benar-benar dirusak (ditambang, TNT, `/setblock ... destroy`). `/setblock` biasa melewati efek samping block entity, sehingga tidak dihitung sebagai rusak oleh mod.

## Protokol (untuk tool lain)

TCP loopback. Setiap frame: `u32 BE panjang header` + header JSON UTF-8 + `u32 BE panjang payload` + payload. Koordinat ke Blender memakai sumbu glTF, relatif terhadap origin level.

| Arah | `t` | Isi |
|---|---|---|
| ke game | `hello` | `level` |
| ke game | `object` / `remove` / `objects` | `name` + payload .glb satu objek / hapus / daftar lengkap |
| ke game | `scene` | payload .glb tanpa mesh (extras + node `spawn`/`sun`) |
| ke game | `commit` | bangun ulang sekarang; dijawab `applied` |
| ke game | `enter`, `command` | teleport pemain ke level / jalankan command (permission penuh) |
| ke Blender | `hello`, `applied`, `status` | origin level, statistik build, pesan |
| ke Blender | `player` | `in`, `p`, `yaw`, `pitch`, `eye` (setiap 2 tick) |
| ke Blender | `block` / `unblock` / `texture` | blok di sel `p` (`id`, `color`) / dihapus / PNG untuk `key` |
| ke Blender | `piece` | sel `p`, `materials`, payload segitiga (25 float LE per segitiga) |
| ke Blender | `hole` / `holes` | satu sel / semua sel lubang |
| ke Blender | `blockmodel` | `key` (state), `quads` (`tex`, `tint`, `emissive`), payload 4 × (xyz + uv) float LE per quad |
| ke game | `camera` / `camera_off` | posisi (`p`), `yaw`, `pitch` viewport Blender / sembunyikan penanda |
| ke Blender | `explosion` | `p`, `r`: TNT meledak |
| ke Blender | `entity_mesh` / `entities` | mesh entity (`id`, `type`, `tex`, `n`, payload UV) / pose tick ini (`list` [id, n], payload posisi xyz) |

## Dev tool

- `gradlew.bat :echoliminal:liveSceneCheck -Pdir=<folder obj_*.glb + scene.glb> [-Pref=level.glb]`: membangun level seperti link live, lalu membandingkannya dengan `.glb` referensi.
- `gradlew.bat runLiminalServer`: server headless di `.tmp/liminal-server` untuk menguji link tanpa jendela game.
