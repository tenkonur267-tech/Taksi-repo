# Taksi Oto Kabul

Sürücü olarak kullandığınız taksi uygulamasına gelen yolcu çağrılarını ekrandan
okuyup, **belirlediğiniz tutar aralığına girenleri otomatik kabul eden** bir
Android uygulaması.

Hedef uygulamanın API'si olmadığı için ağ katmanına dokunulmaz. Uygulama,
Android'in **Erişilebilirlik Servisi** (Accessibility Service) üzerinden ekranda
zaten görünen metni okur ve normalde parmağınızla yapacağınız dokunuşu yapar.

---

## Önce şunu bilin

Bu tür otomasyon, hedef taksi uygulamasının kullanım şartlarını büyük ihtimalle
ihlal eder ve **hesabınızın kapatılmasına** yol açabilir. Ayrıca erişilebilirlik
servisi, seçtiğiniz uygulamanın ekranındaki tüm metni görebilir. Kararı siz
verirsiniz; uygulama bu yüzden **deneme modu açık** olarak gelir: kurallarınızın
doğru çalıştığını kayıtlardan doğrulayana kadar hiçbir düğmeye basmaz.

---

## Nasıl çalışır

```
Ekranda çağrı kartı belirir
        │
        ▼
NodeScanner ──► ekrandaki tüm metinleri toplar
        │
        ▼
AmountParser ──► "₺342,50" gibi tutarları bulur, güven puanı verir
DistanceParser ──► "1,2 km" mesafeyi bulur
        │
        ▼
RuleEngine ──► tutar aralığı, mesafe, kelimeler, saat, bekleme, günlük limit
        │
        ├─ Kabul  ──► Clicker ──► "Kabul Et" düğmesine basar
        ├─ Deneme ──► sadece Kayıtlar sekmesine yazar
        └─ Atla   ──► gerekçesiyle kaydedilir
```

### Tutar ayrıştırma neden bu kadar dikkatli?

Bir çağrı kartında tutardan başka sayılar da vardır: `2,4 km`, `7 dk`,
`4,8 puan`, `%20`. Yanlış sayıyı tutar sanmak, istemediğiniz bir işi kabul
etmek demektir. Bu yüzden `AmountParser` her sayıyı almaz; her adaya bir
**güven puanı** verir:

| Güven | Koşul | Örnek |
|---|---|---|
| 3 | Ücret etiketi **ve** para birimi birlikte | `Tahmini ücret: 190 TL` |
| 2 | Para birimi var | `₺342,50` |
| 2 | Ücret etiketi hemen önünde **ve** kuruş hanesi var | `Tahmini ücret: 342,50` |
| 1 | Sadece ücret etiketi, kuruş hanesi de yok | `Tahmini ücret 190` |

**Güven 2'nin altındaki adaylarla asla otomatik kabul yapılmaz.**

Tanınan yazımlar:

| Yazım | Sonuç |
|---|---|
| `₺342,50` · `342,50 ₺` · `342,50 TL` · `342,50TL` · `TL342,50` · `TRY 342` | tutar |
| `1.250,75` · `1,250` · `125.50` · `1 250,75` (bölünmez boşluklu) | 1250.75 / 1250 / 125.50 / 1250.75 |
| `Kazanacağınız tutar ₺275` — çekim ekli etiketler | tutar |
| `Tahmini ücret: 342,50` — TL işaretini resim olarak çizen uygulamalar | tutar |

Tek ayırıcı varsa kural şu: ardından **tam 3 hane** geliyorsa binlik ayırıcıdır,
1-2 hane geliyorsa ondalık ayırıcıdır.

Elenen sayılar: hemen ardından `km`, `dk`, `puan`, `%` gibi bir birim gelen
sayılar. Bu eleme yalnızca para birimi **görünmeyen** adaylara uygulanır —
`₺185 M. Kemal Mah.` adresteki `M.` yüzünden, `₺185 · %20 kampanya` da yüzde
yüzünden elenmemeli.

---

## Kurulum

### Hazır APK (en kolay)

Her push'ta GitHub Actions APK'yı derleyip sürüm olarak yayınlıyor. Telefonunuzun
tarayıcısından şu adresi açıp indirin:

**https://github.com/tenkonur267-tech/Taksi-repo/releases/tag/apk-latest**

Doğrudan dosya bağlantısı:
`https://github.com/tenkonur267-tech/Taksi-repo/releases/download/apk-latest/taksi-oto-kabul.apk`

İndirirken telefon "bilinmeyen kaynaktan kurulum" uyarısı verirse tarayıcıya izin
verin. APK debug anahtarıyla imzalıdır; Play Store'dan değil, yan yükleme ile kurulur.

### Kaynaktan derlemek

Gereken: Android Studio (Ladybug veya üstü), JDK 17, Android SDK 34.

```bash
git clone https://github.com/tenkonur267-tech/Taksi-repo.git
cd Taksi-repo
./gradlew assembleDebug        # APK: app/build/outputs/apk/debug/
./gradlew test                 # mantık testleri
```

Ya da projeyi Android Studio'da açıp doğrudan telefona kurun. APK'yı elle
kuracaksanız telefonda "bilinmeyen kaynaklara izin ver" gerekir.

---

## İlk ayar (sırayla yapın)

**1. Erişilebilirlik servisini açın.**
Uygulamayı açın → üstteki kırmızı karttan **"Erişilebilirlik ayarlarını aç"** →
listeden **Taksi Oto Kabul** → aç. Karta dönünce yeşile döner.

**2. İzlenecek uygulamayı seçin.**
"Uygulama seç" → telefonunuzdaki taksi uygulamasını işaretleyin. Seçmezseniz
hiçbir şey işlenmez.

**3. Tutar aralığınızı yazın.**
Örneğin en az `150`, en çok boş (sınırsız). İsterseniz azami mesafe de yazın.

**4. BAŞLAT'a basıp deneme modunu AÇIK bırakın ve birkaç çağrı bekleyin.**
Bu en önemli adım. Taksi uygulamasına geçin, çağrılar gelsin, sonra
**Kayıtlar** sekmesine bakın. Her satırda ne okunduğunu ve ne karar verildiğini
görürsünüz:

```
19:42:07  Deneme: kabul edilirdi · 342,50 TL     1,2 km
          Yeni çağrı Kadıköy · Yolcuya 1,2 km · Tahmini kazanç ₺342,50 Kabul Et
          [adaylar: 342,50(g2), 1,2(g1)]  [düğme: "kabul et"]
```

Kontrol edin:
- Okunan tutar gerçekten ekrandaki ücret mi?
- Satır sonunda `[düğme: "..."]` yazıyor mu? `[DİKKAT: kabul düğmesi
  bulunamadı]` yazıyorsa **5. adıma** geçin.

**5. Kabul düğmesinin yazısını düzeltin.**
Kayıtlarda düğme bulunamadıysa, uygulamanızdaki düğmede tam olarak ne yazıyorsa
onu "Kabul düğmesinin yazısı" alanına yazın (`Kabul Et`, `Onayla`, `Yolcuyu Al`
gibi). Virgülle birden fazla yazabilirsiniz.

**6. Deneme modunu kapatın.**
Kayıtlar birkaç çağrı boyunca doğru çıktıktan sonra kapatın. Artık kural uyan
çağrılarda düğmeye basılacak.

**7. BAŞLAT'a basın.**
Uygulamayı kapatabilirsiniz; kontrol arka planda çalışmaya devam eder. Bildirim
gölgesinde "Otomatik kabul çalışıyor" bildirimi durur ve oradaki **Durdur**
düğmesiyle uygulamayı açmadan durdurabilirsiniz.

---

## Ayarlar

| Ayar | Ne işe yarar |
|---|---|
| **BAŞLAT / DURDUR** | Ana kontrol. Durdurulmuşken hiçbir çağrı işlenmez. Çalışırken kalıcı bir bildirim görünür ve oradan da durdurulabilir. |
| **Deneme modu** | Düğmeye basmaz, sadece kaydeder. Kalibrasyon için. |
| **Ekran üstü buton** | Her uygulamanın üstünde duran yuvarlak başlat/durdur butonu. Açık gelir. |
| **İzlenen uygulama** | Sadece işaretlediğiniz uygulamaların ekranı okunur. |
| **En az / En çok (TL)** | Kabul aralığı. Sınır değerleri dahildir. Üst sınır boş = sınırsız. |
| **Azami mesafe (km)** | Yolcu bundan uzaktaysa atlanır. Boş = sınırsız. |
| **Kabul düğmesinin yazısı** | Basılacak düğmenin metni. Yanlışsa hiçbir şey kabul edilmez. |
| **Yasaklı kelimeler** | Biri geçerse çağrı atlanır (`havalimanı` gibi). |
| **Zorunlu kelimeler** | Doluysa, en az biri geçmeyen çağrı atlanır (`nakit` gibi). |
| **İki kabul arası bekleme** | Art arda kabul patlamasını önler. Varsayılan 20 sn. |
| **Günlük kabul sınırı** | Günde en fazla kaç otomatik kabul. Boş = sınırsız. |
| **Çalışma saatleri** | Sadece bu aralıkta çalışır. `22:00-06:00` gibi gece yarısını aşan aralık desteklenir. |
| **Bildirimlerden gelen çağrılar** | Çağrı bildirim olarak geliyorsa bildirimdeki kabul eylemini çalıştırır. |

---

## Başlatma, durdurma ve arka plan

Başlatıp durdurmanın üç yolu var: uygulamadaki büyük düğme, ekranın üstünde
duran baloncuk ve durum bildirimindeki **Durdur**. Üçü de aynı tek anahtarı
çevirir.

### Ekran üstünde duran buton

Uygulama arka plandayken bile ekranın üstünde duran yuvarlak bir buton.
Taksi uygulamasının, haritanın, ana ekranın üstünde kalır:

- **Tek dokunuş** — başlatır / durdurur.
- **Yeşil ▶ BAŞLAT** durdurulmuş, **kırmızı ■ DURDUR** çalışıyor,
  **turuncu ■ DENEME** çalışıyor ama deneme modu açık demektir.
- **Sürükleyin** — istediğiniz yere taşıyın; bıraktığınızda en yakın yan kenara
  yaslanır ve yeri kalıcı olarak hatırlanır.
- **Uzun basın** — uygulamanın ayarlar ekranı açılır.

Butonu **Ayarlar → Ekran üstü buton**'dan kapatabilirsiniz.

Butonu erişilebilirlik servisi çizer. Bunun iki sonucu var: ayrıca "diğer
uygulamaların üzerinde göster" izni istenmez (servis zaten açık olmak zorunda),
ama servis kapalıyken buton da görünmez. Otomatik kabul tam o sırada kabul
düğmesine basıyorsa buton yarım saniyeliğine dokunulmaz olur; böylece basış
butonun altına, taksi uygulamasına gider.

### Uygulama içindeki düğme

Ana kontrol Ayarlar sekmesinin en üstündeki büyük **BAŞLAT / DURDUR** düğmesi.

- **BAŞLAT** — çağrılar arka planda izlenir. Uygulamayı kapatabilirsiniz;
  çağrıları yakalayan erişilebilirlik servisi sistem tarafından ayakta tutulur.
  Çalışırken kalıcı bir durum bildirimi görünür: tutar aralığınızı, bugünkü
  kabul sayısını ve bir **Durdur** düğmesi taşır.
- **DURDUR** — hiçbir çağrı işlenmez, bildirim kalkar.

Düğme erişilebilirlik servisi kapalıyken ya da izlenecek uygulama seçilmemişken
etkisizdir; kart bunun nedenini yazar.

Durum bildirimi tek başına bir şey yapmaz — çağrıları yakalayan erişilebilirlik
servisidir. Bildirimin işi durumu görünür kılmak, tek dokunuşla durdurmayı
sağlamak ve bellek baskısında sürecin öldürülme ihtimalini düşürmek. Android 13
ve üstünde bildirim izni reddedilirse uygulama yine çalışır, yalnızca bildirim
görünmez.

## Çalışmıyorsa: tanılama modu

"Çağrılar geliyor ama uygulama hiçbir şey yapmıyor" durumunda **Ayarlar → Diğer →
Tanılama modu**'nu açın, sonra Kayıtlar sekmesine bakın. Normalde sessiz geçilen
her adım orada görünür:

| Kayıt | Anlamı | Yapılacak |
|---|---|---|
| Hiç kayıt yok | Servis olay almıyor | Durum kartını kontrol edin; "açık görünüyor ama çalışmıyor" yazıyorsa servisi kapatıp açın |
| `Ekranda: com.filan.app · bu uygulama izlenmiyor` | Çağrı sırasında ekranda olan uygulama seçtiğinizden farklı | Kayıttaki paket adını "Uygulama seç"ten işaretleyin |
| `Pencere okunamadı` | Uygulama ekranını erişilebilirliğe kapatıyor (`FLAG_SECURE`) | Yapılabilecek bir şey yok |
| `Çağrı kartı değil` + okunan metin | Ekran okundu ama çağrı sayılmadı | Metinde tutar `₺`/`TL` ile görünüyor mu, kabul düğmesinin yazısı ayarlardakiyle aynı mı bakın |
| `Atlandı · Tutar okunamadı` | Kart tanındı, tutar bulunamadı | Kayıt bu durumda ekrandan okunan metnin daha uzununu tutar; o satırı bana gönderin, ayrıştırıcıyı ona göre ayarlayayım |
| `Atlandı · Tutar güvenilir değil` | Sayı bulundu ama para olduğuna güvenilmedi | Aynı şekilde kaydı gönderin; genelde TL işaretinin resim olarak çizilmesinden olur |
| `Atlandı · Kabul düğmesi bulunamadı` | Karar verildi ama basılacak düğme yok | Düğmedeki yazıyı birebir ayarlara girin |

Kayıt satırındaki ham metin, ekrandan gerçekten ne okunduğunu gösterir; sorunu
çözmenin en hızlı yolu o satırı paylaşmaktır.

## Bilinen sınırlar

- **Ekranda görünmeyen çağrı kabul edilemez.** Taksi uygulaması önde değilse ve
  çağrıyı bildirim olarak da göstermiyorsa uygulama onu göremez.
- **Tutarı okunamayan çağrı asla kabul edilmez.** Bu bilinçli bir karar: kör
  kabul yapmaktansa çağrıyı kaçırmak yeğdir.
- **Kayıtlar bellekte tutulur**, uygulama süreci kapanınca silinir. Kalibrasyon
  için yeterli, arşiv değil.
- Hedef uygulama arayüzünü değiştirirse (düğme yazısı, tutar biçimi)
  ayarları güncellemeniz gerekir. Deneme modunu tekrar açıp doğrulayın.
- Bazı uygulamalar `FLAG_SECURE` ile ekranı erişilebilirliğe kapatır; bu
  durumda hiçbir metin okunamaz.
- Kabul düğmesi bir resimse ve içerik açıklaması (contentDescription) da
  taşımıyorsa bulunamaz; erişilebilirlik ağacında yazı olarak görünmesi gerekir.

---

## Proje yapısı

```
app/src/main/kotlin/com/taksi/autoaccept/
├── core/                    saf mantık, Android'e bağımlı değil, test edilir
│   ├── parse/AmountParser   tutar ayrıştırma + güven puanı
│   ├── parse/DistanceParser mesafe ayrıştırma
│   ├── rules/RuleEngine     kabul/ret kararı (saf fonksiyon)
│   ├── model/               RideRequest, FilterSettings
│   └── log/                 karar kayıtları
├── data/SettingsRepository  DataStore ile kalıcı ayarlar, sayaçlar, buton konumu
├── overlay/
│   ├── OverlayBubble        ekranın üstünde duran başlat/durdur butonu
│   └── OverlayPlacement     butonun konumu: sınırlama ve kenara yaslama (saf, test edilir)
├── service/
│   ├── RideAcceptAccessibilityService   ekranı ve bildirimleri izler
│   ├── NodeScanner                      metin toplama, düğme bulma
│   └── Clicker                          tıklama (düğüm eylemi → dokunma jesti)
├── ui/                      Compose arayüz: Ayarlar + Kayıtlar
└── util/                    erişilebilirlik durumu, kurulu uygulama listesi
```

`core` katmanı kasıtlı olarak Android sınıfı içermez; kararın tamamı
`RuleEngine.decide(request, context)` içinde ve zaman/sayaç dışarıdan verilir.
Bu sayede tutar ayrıştırma ve kural mantığı JVM testleriyle doğrulanır:

```bash
./gradlew test
```

---

## Lisans

Kişisel kullanım için. Kullanımdan doğan sorumluluk kullanıcıya aittir.
