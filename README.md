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
| 1 | Sadece ücret etiketi, para birimi yok | `Tahmini ücret 190` |

**Güven 2'nin altındaki adaylarla asla otomatik kabul yapılmaz.** Sayının hemen
ardından `km`, `dk`, `puan`, `%` gibi bir birim geliyorsa o sayı baştan elenir.

Sayı biçimi hem Türkçe hem İngilizce yazımı çözer: `1.250,75` → 1250.75,
`1,250` → 1250, `125.50` → 125.50. Tek ayırıcı varsa kural şu: ardından **tam 3
hane** geliyorsa binlik ayırıcıdır, 1-2 hane geliyorsa ondalık ayırıcıdır.

---

## Kurulum

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

**4. Deneme modunu AÇIK bırakın ve birkaç çağrı bekleyin.**
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

---

## Ayarlar

| Ayar | Ne işe yarar |
|---|---|
| **Otomatik kabul açık** | Ana anahtar. Kapalıyken hiçbir çağrı işlenmez. |
| **Deneme modu** | Düğmeye basmaz, sadece kaydeder. Kalibrasyon için. |
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
├── data/SettingsRepository  DataStore ile kalıcı ayarlar ve sayaçlar
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
