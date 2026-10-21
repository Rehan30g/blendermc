# Custom World (dimensi liminal)

Dimensi `blendermc:liminal` berisi level yang **bukan blok voxel**. Setiap level adalah model 3D dari Blender (file `.glb`) yang digambar oleh renderer custom. Pemain tetap bisa berjalan di permukaannya, menaruh dan menghancurkan blok, dan potongan geometrinya bisa diambil lalu dipasang lagi.

Dokumen ini menjelaskan cara memakai, membuat, dan merawat level. Panduan singkat untuk mengedit level hallway di Blender ada di `tools/liminal/README.md`.

---

> **Edit live dari Blender:** scene Blender bisa langsung menjadi level yang berubah realtime di game, dan pemain, blok, serta lubang dari game kembali ke Blender. Lihat [BLENDER-LIVE.md](BLENDER-LIVE.md).

## 1. Pemakaian cepat

| Command | Fungsi |
|---|---|
| `/blendermc` | masuk ke level aktif (level tempatmu berdiri, atau yang terakhir dimasuki) |
| `/blendermc enter <nama>` | teleport ke spawn level itu; saat pertama kali, level diisi lebih dulu |
| `/blendermc leave` | kembali ke posisi sebelum masuk (atau ke titik respawn) |
| `/blendermc reload` | membaca ulang semua `.glb` tanpa restart game (memuat ulang resource beberapa detik) |
| `/blendermc restore` | mengembalikan semua geometri yang hancur **dan** batu yang sudah digali di level tempatmu berdiri |
| `/blendermc time <0..24000>` | uji siklus siang/malam: melompatkan waktu level tempatmu berdiri (0 terbit, 6000 siang, 12000 terbenam, 18000 tengah malam) |

Di mode dev, command ini selalu boleh dipakai walaupun cheat mati. Untuk command vanilla, buka world dengan cheat aktif (atau `/haunt cheats on` kalau Echo World ikut terpasang).

Level bawaan:

| Nama | Isi | Setting utama |
|---|---|---|
| `hallway` | backrooms prosedural: lorong, ramp, tangga buntu, ruang salinan, ruang tinggi | indoor gelap, tanpa matahari, `stone_fill=false` |
| `map79` | gurun dari map unduhan, dua tiang lampu, balok laser | langit merah DOOM (skybox), matahari, kabut merah, berisi batu, siklus siang/malam (`day_cycle`, 20 menit) |
| `room` | scan fotogrametri ruangan terbengkalai + patung Roblox duduk | `unlit` (cahaya foto asli), tanpa batu, kotak tabrakan tak terlihat |

---

## 2. Di mana file level disimpan

- **Ikut mod (perlu build ulang):** `src/main/resources/assets/blendermc/liminal/<nama>.glb`
- **Tanpa build:** `run/config/blendermc/liminal/<nama>.glb`. Folder ini **dibaca lebih dulu**, jadi file di sini menimpa versi bawaan dengan nama yang sama. Setelah menaruh file, jalankan `/blendermc reload`.

Nama level = nama file tanpa `.glb`.

---

## 3. Membuat level di Blender

### Skala dan sumbu
- **1 meter Blender = 1 blok.** Pemain tinggi 1,8 dan lebar 0,6. Lorong yang enak dijalani lebarnya sekitar 3 dengan tinggi 3–4.
- Z ke atas di Blender. Exporter glTF otomatis mengubah ke Y-up. Titik (0,0,0) Blender jadi origin level di game.
- Level boleh membentang sampai sekitar 4.000 blok dari (0,0,0).

### Nama objek dan material yang punya arti

| Nama | Arti |
|---|---|
| Empty `spawn` | posisi kaki pemain saat masuk. Arah hadap = sumbu lokal **-Y** Blender. Putar hanya di sumbu Z. |
| Empty `sun` | arah cahaya matahari = sumbu lokal **-Z** (seperti lampu Sun Blender) |
| material berisi `light`, atau material dengan emission > 0 | menyala: digambar fullbright dan menjadi sumber cahaya (area light) |
| material berakhiran `_nocollide` | hanya tampilan, tanpa tabrakan (decal, noda, scan visual) |
| material berakhiran `_thin` | permukaan tipis: tidak punya volume padat di belakangnya (sinar melayang, papan tipis) |
| material berakhiran `_sky` | skybox: digambar mengikuti kamera, fullbright, tanpa tabrakan, tidak bisa dihancurkan. (0,0,0) = posisi mata. |

Material yang menyala dan `_nocollide` otomatis dianggap tipis juga.

Trik **tabrakan tak terlihat**: buat mesh sederhana dengan material ber-alpha 0 (Blend Mode: Alpha Clip). Mesh itu tidak tergambar tapi tetap bisa diinjak. Ini dipakai di level `room`: scan-nya `_nocollide`, lalu ada kotak lantai, dinding, dan langit-langit yang tak terlihat.

### Metadata level (Scene Properties > Custom Properties)

Saat ekspor, centang **Include > Custom Properties**.

| Key | Tipe | Default | Arti |
|---|---|---|---|
| `sky_color` | `"#rrggbb"` | hitam | warna langit di belakang skybox |
| `fog_color` | `"#rrggbb"` | warna langit | warna kabut |
| `fog_start`, `fog_end` | angka (blok) | tanpa kabut | jarak kabut mulai dan habis |
| `time_of_day` | 0..24000 | — | posisi matahari di langit (0 = terbit di timur/+X, 6000 = siang); dengan `day_cycle` jadi waktu awal |
| `sun_dir` | `[x, y, z]` | — | arah cahaya matahari dalam sumbu glTF; mengalahkan Empty `sun` |
| `sun_visible` | bool | false | matahari tergambar di langit |
| `day_cycle` | bool | false | matahari dan bulan bergerak, ada siang dan malam (lihat bagian siklus siang dan malam di bawah) |
| `day_length` | angka (detik) | 1200 | lama satu hari penuh saat `day_cycle` menyala |
| `skylight` | bool | false | ada cahaya langit (outdoor). `false` = gelap kecuali lampu. |
| `stone_fill` | bool | **true** | volume di balik permukaan diisi batu (`false` = berongga, misalnya backrooms) |
| `unlit` | bool | false | tekstur tampil apa adanya tanpa cahaya tambahan (untuk scan foto yang cahayanya sudah ada di tekstur) |

### Siklus siang dan malam (`day_cycle`)

Level outdoor bisa punya matahari dan bulan yang bergerak, dengan malam yang gelap dan siang yang terang. Aktifkan dengan dua key di Scene Properties:

- `day_cycle = true` menyalakannya. Perlu `skylight = true` dan `sun_dir` (atau `time_of_day`). Tidak berlaku untuk level `unlit`.
- `day_length` = detik untuk satu hari penuh (default 1200 = 20 menit, sama seperti vanilla).

Cara kerjanya:
- **Waktu diturunkan dari waktu game server**: `t = (time_of_day + gameTime × 24000 / (day_length × 20)) mod 24000`. `time_of_day` jadi waktu awal (saat `gameTime` = 0). Karena dihitung dari waktu dunia, server dan klien selalu sama dan waktu lanjut setelah restart.
- **Jalur matahari** adalah busur vanilla (terbit di t = 0, puncak di 6000, terbenam di 12000) yang dimiringkan supaya pada `time_of_day` matahari tepat berada di `sun_dir`. Bulan selalu di sisi berlawanan. Jadi tampilan statis yang dibuat di Blender tetap sama pada waktu itu.
- **Pencahayaan tidak dihitung ulang tiap frame.** Saat bake (di latar belakang) bayangan matahari dihitung untuk 12 posisi matahari sepanjang siang. Per vertex disimpan seberapa terlihat matahari di tiap posisi, ditambah bagian yang tidak bergantung waktu (kubah langit, AO, lampu, pantulan). Saat jalan, game menginterpolasi dua posisi terdekat dan mengalikan dengan faktor siang; 1/20 vertex ditulis ulang per tick. Medan cahaya (blok dan entity) memakai waktu yang sama; bagian blok vanilla di-mesh ulang bertahap di sekitar pemain setiap beberapa puluh detik. Bake map79 jadi sekitar 25–30% lebih lama (sinar matahari untuk 12 posisi).
- **Malam**: tidak ada matahari, cahaya langit tinggal sekitar seperlima dan berwarna biru dingin (cahaya bulan), langit dan kabut jadi 7–17% dari warna siang dengan nuansa biru, skybox digelapkan. Saat terbit dan terbenam ada semburat hangat.
- **Matahari dan bulan** digambar sebagai billboard bundar yang buram, bertekstur prosedural (tanpa tekstur vanilla): matahari berwarna hangat (merah-oranye di dekat horizon) dengan cahaya lebar, bulan lebih kecil dan pucat. Keduanya menghilang pelan di bawah horizon. Matahari hanya tergambar jika `sun_visible = true`.

Untuk menguji tanpa menunggu: `/blendermc time <0..24000>` melompatkan waktu level tempatmu berdiri (0 terbit, 6000 siang, 12000 terbenam, 18000 tengah malam). Lompatan hanya disimpan di memori (hilang saat game ditutup) dan tidak ikut dikirim ke pemain yang masuk belakangan.

### Ekspor
File > Export > glTF 2.0:
- Format **glTF Binary (.glb)**
- Include: **Renderable Objects** dan **Custom Properties**
- Transform: **+Y Up**
- Mesh: **Apply Modifiers**
- Material: Export, Images: Automatic
- Tekstur harus **PNG**. JPEG tidak terbaca. Pakai interpolasi **Closest** supaya pixelated.

Saat dimuat, mesh otomatis dipotong pada grid 1 blok, jadi ekspor manual pun aman. Tidak perlu memecah face besar sendiri.

### Tips
- Dinding sebaiknya tebal atau tertutup, supaya pemain tidak bisa melihat void lewat celah.
- Arah normal permukaan penting: sisi depan dianggap "luar", sisi belakang dianggap "dalam" (volume). Normal terbalik membuat obor tidak bisa menempel dan batu terisi di tempat yang salah.
- Tekstur besar membuat file dan memori membengkak. 2048 px sudah cukup.
- Hindari triangle sangat banyak dalam satu blok. Scan fotogrametri sebaiknya di-decimate dulu.

---

## 4. Cara kerja di dalam game

### Setiap level punya wilayah sendiri
Semua level ada di dimensi yang sama tapi di wilayah berbeda: origin = `slot × 8192` di sumbu X (Y=64, Z=0). Slot diberikan saat level pertama kali dimasuki dan dicatat di `blendermc.json` di folder world. Blok yang kamu bangun di satu level tidak muncul di level lain.

**Jangan menghapus atau mengedit `slots` di file itu**, karena posisi level akan bergeser.

### Geometri = blok `mesh_cell`
Setiap sel 1×1×1 yang berisi geometri menjadi blok `blendermc:mesh_cell` yang tak terlihat sebagai kubus. Isinya:
- **Tabrakan segitiga sungguhan.** Lereng benar-benar miring, step-up vanilla tetap jalan, dan sisi belakang permukaan bisa diinjak tanpa melayang.
- **Bidikan dengan ketebalan**, jadi dinding vertikal bisa diklik untuk menaruh blok.
- **Outline berupa garis segitiga**, bukan kotak.
- Obor, api, karpet, rel, dan pressure plate bisa ditaruh di permukaannya.

### Menghancurkan dan memasang lagi
- Menambang sel mesh memberi item **"fragment of <level>"** yang menyimpan potongan geometri dan teksturnya. Item ini bisa dipasang di mana saja, termasuk di overworld, dan menghadap ke pemasang.
- Sel yang hancur (ditambang, TNT, `/fill`, dan sebagainya) dicatat per level, tetap hancur setelah restart, dan bisa dikembalikan dengan `restore`.
- **Menaruh blok di sel lantai yang dangkal** membuat blok sedikit tenggelam. Geometrinya tetap terlihat, dan kembali utuh begitu blokmu diambil.
- Entity yang tertanam di belakang permukaan (misalnya TNT yang dinyalakan di dalam lantai) didorong keluar ke permukaan.

### Volume dan batu
- Level dengan `stone_fill=true` diisi `blendermc:liminal_stone` di balik permukaannya. Pengisiannya sekali per level; batu yang sudah digali tidak diisi ulang kecuali lewat `restore`.
- Sisi batu **hanya tergambar di dalam lubang galian**, jadi batu tidak pernah menembus permukaan.
- Di sisi lubang yang memotong sel mesh, renderer menggambar **dinding potongan** bertekstur stone. Dinding ini hanya muncul di sebelah sel yang benar-benar digali.
- Level berongga (`stone_fill=false`): di balik permukaan kosong. Lubang galian tetap ditutup dinding potongan.

### Langit, kabut, cahaya
- Langit, matahari, bulan, bintang, awan, dan kabut Minecraft dimatikan di dimensi ini. Yang tampil hanya skybox level dan kabut level, yang berlaku sama untuk mesh, blok, entity, dan Sodium. Pada level dengan `day_cycle`, matahari dan bulan buatan sendiri (billboard buram) ikut tampil, dan warna langit, kabut, serta cahaya langit mengikuti waktu.
- **Pencahayaan ray-trace yang dihitung lebih dulu**, di latar belakang:
  - bayangan matahari yang lembut (dengan `day_cycle`: 12 posisi matahari sepanjang siang, diinterpolasi saat jalan);
  - cahaya panel neon, obor, dan cahaya lain yang tidak tembus dinding;
  - ambient occlusion;
  - satu kali pantulan cahaya.

  Saat dimuat, pencahayaan lama tampil dulu sampai hitungannya selesai. Menaruh atau menghancurkan obor atau blok hanya menghitung ulang area sekitarnya.
- Blok vanilla, entity, dan potongan yang dipasang memakai **medan cahaya yang sama** dengan mesh. AO dan smooth lighting Minecraft dimatikan di dimensi ini.
- Panel neon juga memasang blok cahaya tak terlihat (`minecraft:light`) di depannya.
- Level `unlit` melewati semua itu: tekstur fullbright, dan terang entity serta blok diambil dari rata-rata terang tekstur level, ditambah obor.

---

## 5. Alat bantu

| Perintah | Fungsi |
|---|---|
| `./gradlew glbCheck` | memuat `hallway.glb` lalu mencetak jumlah segitiga, sel, sisi terpanjang, spawn, klasifikasi volume, serta simulasi jalan, sneak, sisi belakang, TNT, klik, dan waktu hitung cahaya |
| `./gradlew glbCheck -Pglb=path/a.glb;path/b.glb` | sama, untuk level lain |
| `./gradlew cutCheck -Pglb=path/level.glb` | menghitung dinding potongan (tanpa galian harus 0) |
| `tools/liminal/make_hallway.py` | membuat ulang level hallway (Blender headless) |
| `tools/liminal/make_map79.py` | mengonversi map gurun unduhan (langit merah, matahari) |
| `tools/liminal/make_room.py` | mengonversi scan ruangan dari `D:\Blender 3D Proj\BLENDER_FILE\ROOM.blend` |
| `tools/liminal/preview_map79.py [-- level.glb]` | render pratinjau Eevee dengan tekstur asli, skybox, dan matahari |

Menjalankan script Blender secara headless:
```
"C:\Program Files\Blender Foundation\Blender 5.2\blender.exe" --background --disable-autoexec <file.blend> --python tools/liminal/<script>.py
```
Pakai `--disable-autoexec` untuk file dari internet.

**Jangan menjalankan gradle (compile atau check) saat game sedang jalan.** Compile ulang di tengah `runClient` membuat game crash.

---

## 6. Batasan yang diketahui

- **Sodium:** chunk Sodium memakai kabut level, tapi tidak memakai medan cahaya liminal. Untuk tampilan yang menyatu penuh, jalankan dengan `-Pdev_sodium=false`.
- `liminal_stone` tidak menghalangi cahaya, jadi cahaya bisa tembus batu di antara dua terowongan.
- Siklus siang/malam: bayangan antar-posisi matahari adalah interpolasi (bayangan melompat halus, tidak bergeser mulus); cahaya blok vanilla ikut bergerak lewat mesh ulang bertahap, jadi ada jeda beberapa detik. Sodium tidak memakai medan cahaya, jadi di sana hanya terang global (siang/malam) yang ikut.
- Map79 berat: sekitar 145 ribu segitiga, 33 ribu sel, dan sekitar 470 ribu batu. Masuk pertama kali bisa patah beberapa detik.
- Blok fungsional (chest, furnace, pintu, kasur) mengikuti grid biasa. Penempatan di luar grid sudah dihapus.
- Hanya set UV pertama yang dibaca. Tekstur pada set UV kedua (misalnya wajah patung di `room`) bisa tampil salah.
- Bayangan dari benda yang bergerak (pemain dan mob) belum ada. Bayangan hanya dari geometri statis dan blok.
- Mob tidak tahu jalan di atas mesh (pathfinding melihat sel mesh sebagai blok utuh).

---

## 7. Mengatasi masalah

| Gejala | Coba |
|---|---|
| Level tidak ditemukan | cek nama file, lalu `/blendermc reload`. Lihat log untuk error saat memuat .glb. |
| Tekstur putih | teksturnya bukan PNG, atau tidak tertanam di .glb |
| Langit atau kabut terlihat seperti Minecraft biasa | pastikan kamu di dimensi liminal dan metadata ikut terekspor (Include > Custom Properties) |
| Ada batu atau tekstur stone di tempat yang aneh pada world tes lama | hapus folder `dimensions/blendermc/liminal` di world itu, lalu masuk lagi |
| Pencahayaan lama tidak berubah | tunggu hitungan latar belakang selesai (map79 sekitar 3–4 detik). Waktunya tercatat di log. |
| Game crash saat start setelah build baru | kirim crash report dari `run/crash-reports/`. Biasanya ada mixin yang tidak cocok. |

---

## 8. Dunia tanpa batas (endless)

Dimensi `blendermc:liminal_endless` berisi dunia liminal prosedural yang tidak punya akhir. Bentuknya dihitung dari seed dunia (sama di server dan klien), bukan dari file `.glb`. Interaksinya sama dengan level biasa: tabrakan segitiga sungguhan, menggali, menaruh blok, fragmen.

### Pemakaian
| Command | Fungsi |
|---|---|
| `/blendermc endless` | masuk ke dunia ini (muncul di gedung backrooms dekat 0,0); posisi sebelumnya disimpan |
| `/blendermc leave` | kembali ke posisi sebelum masuk |
| `/blendermc restore` | (di dimensi ini) memasang lagi sel geometri yang hancur di chunk yang termuat |

### Zona
- **Backrooms**: gedung dengan ruangan tak beraturan, dinding miring dan melengkung, ambang pintu, lengkungan, pilar segi delapan, partisi bersudut, panel lampu neon.
- **Poolrooms**: ubin putih, kolam di atas panggung bundar bertingkat dengan tepi miring, lengkungan, langit-langit tinggi.
- **Field**: padang rumput bergelombang dengan teras dan dataran tinggi, tiang listrik dan kabel, lubang berlereng, piramida bertingkat (ziggurat), monolit miring, kusen pintu raksasa, platform melayang dengan tangga spiral.

Tidak ada bentuk yang selaras grid: dinding punya sudut, posisi, dan ketebalan bebas; sudut dipotong miring. Pemotongan pada grid 1 blok hanya terjadi di dalam (untuk sel dan tabrakan).

### Vertikal
Setiap sel gedung (32x32 blok) punya beberapa lantai: dari 1 sampai 12 lantai ke atas dan sampai 4 lantai ke bawah tanah (tinggi lantai 4,8 sampai 7,2 blok). Satu modul berisi **tangga spiral** yang menghubungkan semua lantai (kadang menembus langit-langit), dan modul lain bisa berupa **atrium** (lubang terbuka beberapa lantai dengan pagar). Ada juga ruang tenggelam, tangga menuju kusen di udara, dan lempengan melayang. Rentang tinggi dunia: y 16 sampai 176. Lantai dasar selalu y=64.

Ruang bergerak: lorong minimal 1,5 lebar pintu dan 2,35 tinggi, lengkungan 2,4 lebar, anak tangga 0,4, lereng maksimal sekitar 30 derajat.

### Keterhubungan
Setiap modul punya satu sisi wajib terbuka ke tetangga barat atau utaranya, jalan utama (setiap modul keempat) selalu terbuka, dan setiap modul keempat di batas antar sel gedung/padang selalu berupa pintu atau lengkungan. Tangga inti menghubungkan semua lantai. Diperiksa oleh `EndlessConnectivity`.

### Level of detail (jarak dari pemain)
| LOD | jarak | isi |
|---|---|---|
| 0 | sampai 80 | geometri penuh bertekstur, lampu baked, sel bisa dihancurkan |
| 1 | sampai 112 | segitiga utuh tanpa detail kecil, warna rata |
| 2 | sampai 288 | siluet kolom 4 blok |
| 3 | sampai 640 | siluet kolom 8 blok |
| 4 | sampai 2048 | siluet kolom 16 blok |

Kabut tiap zona menyembunyikan batas antar cincin. Tekstur 128 px dengan mipmap dan filter linear, skala dunia nyata (satu pengulangan per 2 sampai 4 blok).

### Alat
| Perintah | Fungsi |
|---|---|
| `./gradlew endlessExport -Pseed=12345 -Pregion=0,0 -Pradius=1 -Plod=0 [-Pout=file.glb]` | menulis area sebagai `.glb` (bisa dibuka di Blender) dan mencetak waktu dan jumlah segitiga per LOD. Pakai `-Plod=1` atau lebih untuk file kecil; LOD 0 radius 1 sekitar 150 MB |
| `java -cp ... dev.blendermc.tools.EndlessConnectivity <seed> <radius>` | memeriksa semua lantai tiap modul terhubung ke titik muncul (ada juga di kelas `ConnectivityCheck`) |
| `tools/endless/preview.py` | render pratinjau Blender dari `.glb` hasil ekspor (`blender --background --factory-startup --python tools/endless/preview.py -- in.glb awalan`) |

### Batasan
- LOD 0 sekitar 60 ribu segitiga per region 64x64 (jauh lebih berat dari versi sebelumnya); karena itu cincin LOD 0 hanya 80 blok. Segitiga LOD 0 tidak dipotong per sel: sel yang digali menyembunyikan segitiga yang pusatnya ada di sel itu (bisa tersisa serpihan kecil).
- Dinding potongan (cut faces) untuk lubang galian di mesh endless belum dipasang; batu di bawah padang hanya tampil di lubang galian.
- Belum ada: tangga Escher yang berputar kembali, ruangan miring, ruang sempit sangat tinggi, air-terjun tangga.
- Dunia lama dari versi sebelumnya (voxel) harus dibuat ulang (hapus `dimensions/blendermc/liminal_endless` di world tes).
