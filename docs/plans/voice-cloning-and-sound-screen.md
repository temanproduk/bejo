# Plan: voice cloning ("Suara Kamu Sendiri"), a simpler "Suara Uang Masuk" screen, and a confirm step on Edit Profil

Status: **proposal, nothing built.** Written 2026-10-14. Parts A and B should ship together, because the new
voice feature needs a home on a screen that is already too crowded. Part C is small and independent.

- **Part A** — clone the merchant's own voice from a short intro clip and **generate the individual pack clips
  automatically** with it. Announcements are still built by gluing single-word clips, exactly as today; the clone
  only replaces the recording step, and the glued amount must **sound as natural as possible**.
- **Part B** — simplify the Profil → *Suara Uang Masuk* screens (and the Profil page itself).
- **Part C** — Edit Profil: show a confirmation dialog when the user taps save.

**Decided (2026-10-14):**
- Only the **Akun Utama** may create a cloned voice. It belongs to the business and is available to every device
  of that business (see A5).
- **The strategy stays "glue single-word clips".** The announcer, the pack format and playback are **not
  changed**; cloning only generates the individual clips. Natural-sounding glued amounts are the quality bar
  (see A6b).

---

## Part A — Voice cloning for sound packs

> **Superseded (2026-10-16)** by [2026-10-16-voice-clone-pack-design.md](2026-10-16-voice-clone-pack-design.md): the
> voice is now cloned **on the device** (no Google allow-list, no server, no upload of the recording), and the number
> clips come from converting the bundled human voice, because generating word clips with a speech model did not glue
> acceptably. The text below is kept for the quality-bar ideas (A6b) and the privacy/legal list (A7).

### A1. What we want

On the sound-pack screen the user **records or uploads an intro** (a few seconds). Then:

- If the intro contains a **human voice** → the app clones that voice and **generates every number clip
  automatically**, so announcements ("dua ratus ribu") sound like the user.
- If the intro has **no human voice** (a chime, music, silence) → the intro is used as the custom intro and the
  numbers keep the **default voice**. Nothing is cloned or uploaded.
- Either way, the glued amount must sound **as natural as possible** (see A6b).

### A2. How sound packs work today (so the new pack fits)

- **How an announcement is built today** (`TransactionAnnouncer`): `spellOutNumber` turns the amount into Indonesian
  words ("dua ratus lima puluh ribu"; `satu ribu` → `seribu`), each word is looked up as a clip key, and
  `intro` + the word clips are chained **gapless** with `MediaPlayer.setNextMediaPlayer`. There is **no
  "rupiah" clip** (the README example mentions one, the code and keys do not have it). Every amount is therefore
  a concatenation of single words. That stays; the work is to make generated clips glue well (see A6b).
- A pack = one clip per key: `intro`, `satu`…`sembilan`, `sepuluh`, `sebelas`, `seratus`, `seribu`, `belas`,
  `puluh`, `ratus`, `ribu`, `juta`, `milyar`, and `ping`. In a zip they are `NN_key.ext` (`00_intro.wav` …
  `19_milyar.wav`, `20_ping.mp3`). **19 words to generate (≈100 characters of text).** The pack format does not change.
- The app already imports such a zip: `ZIP_EXTRACTED` packs are extracted into `filesDir/soundpacks/<id>/` and
  matched to keys by filename (an internal capability, not in the UI). A generated pack can reuse it (add a
  `CLONED` type for the label and for "delete").
- Clip format shipped today: 16-bit PCM mono 44.1 kHz WAV, silence trimmed, then **+12 dB into a limiter at
  −0.2 dBFS** (`soundpacks/build.sh`). Generated clips must get the same treatment or they will sound quiet next
  to the bundled voices.
- `WavSpeed` (pitch-preserving speed change) reads the sample rate from each WAV header and needs **16-bit PCM**;
  so Google's 24 kHz `LINEAR16` clips work **without resampling**. Verify gapless chaining of mixed sample rates.
- The app has **no `RECORD_AUDIO` permission yet** (only `READ_MEDIA_AUDIO`), so this adds a permission.

### A3. What Google's API gives us (checked against the docs on 2026-10-14)

Source: [Chirp 3: Instant Custom Voice](https://docs.cloud.google.com/text-to-speech/docs/chirp3-instant-custom-voice).

| Topic | Finding |
|---|---|
| Access | **Restricted: allow-listed per project; request through Google Cloud sales.** This is the first blocker. |
| Indonesian | **Supported (`id-ID`)** — one of 24 locales, with an Indonesian consent script. |
| Reference audio | **Up to 10 s**, mono; `LINEAR16`/`PCM`/`MP3`/`M4A`; any sample rate; low background noise. The model reproduces microphone quality. |
| Consent | The voice owner must **record a spoken consent statement** (≤ 10 s) in the same environment as the reference, using Google's script. Indonesian: *"Saya pemilik suara ini dan saya menyetujui Google menggunakan suara ini untuk membuat model suara sintetis."* **An uploaded intro alone is therefore not enough; the user must also speak the consent line in the app.** |
| Create key | `POST …/v1beta1/voices:generateVoiceCloningKey` with `reference_audio`, `voice_talent_consent`, `consent_script`, `language_code` → `voiceCloningKey`. |
| Synthesize | `POST …/v1beta1/text:synthesize` with `voice.voice_clone.voice_cloning_key`; `LINEAR16` 24 kHz by default. Pace 0.25–2×; pause tags and IPA pronunciation are experimental. |
| Key storage | **Keys are stored by us, not Google** ("client-side"); we pass the key with each request. A leaked key lets anyone synthesize that voice, so it is a secret. |
| Limits | **10 new keys per minute per project.** |
| Regions | Includes `asia-southeast1` (Singapore): lower latency, still outside Indonesia, so it must be disclosed. |
| Stability | **`v1beta1`** — expect changes; isolate it behind one interface. |
| Price | Not on that page. Third-party sources quote **$60 per 1M characters, no free tier** (unverified; confirm on Google's price page). The 19 words are ≈100 characters (**≈ $0.006**); generating several takes of list-style readings (A6b) is a few hundred characters, **roughly $0.01–$0.05 per business**. Cost is not the constraint; access, quality and consent are. |

Not documented, to confirm with Google during the spike: whether the consent recording is **voice-matched
against the reference** (which would also stop one person cloning another), and how it handles reference audio
that mixes speech with music.

### A4. Proposed user flow (Android)

Only the **Akun Utama** sees "Suara Kamu Sendiri" (Akun Karyawan can use the finished voice but not create, replace
or delete it).

1. **Intro** — *Rekam* (mic, ≤ 10 s, level meter) or *Unggah* (audio file, with a simple trim to ≤ 10 s).
2. **Check (on device)** — voice-activity detection + loudness/noise check.
   - Human voice found → continue.
   - **No human voice → save as "intro kustom + angka suara standar"; stop here** (no consent, no upload).
   - Too short / too noisy → "rekam ulang" with a specific hint.
3. **Consent** — plain-language explanation, then the **voice owner reads the consent sentence aloud** (recorded
   in-app), plus a checkbox for Bejo's own processing consent (separate from Google's script). The voice owner
   should be the person holding the phone; the Akun Utama also warrants in the Terms that whoever's voice it is
   has agreed.
4. **Generate** — upload both clips, show progress (~1–2 min), push notification when ready (reuse FCM). Replacing
   an existing cloned voice asks for confirmation (there is one active cloned voice per business).
5. **Listen** — the existing amount simulator, then **Terapkan / Ulangi / Hapus suara saya**.

Rules: the previously active pack is untouched until the user taps *Terapkan*; any failure falls back to the
default; the finished pack is stored locally, so announcements work **offline** after download.

### A5. Architecture (Bejo backend)

The app cannot call Google directly (credentials, allow-list, cost, abuse control), so generation runs on the server.

- **New module `voice`** (depends on `core` + `shared`, like `ads`; update `ArchitectureTest`), own schema `voice`,
  no cross-schema FKs (same rule as the other modules).
- **Tables:** `voice_clone_jobs` (id, business_id, username, status `PENDING → CHECKING → GENERATING → READY |
  FAILED | NO_SPEECH`, locale, consent text + timestamp + audio path, error, timings) and `voice_profiles`
  (the cloning key, **encrypted at rest** — AES-GCM with a key from Secret Manager, or Cloud KMS).
- **Storage:** a private GCS bucket `<project>-voice` (uniform access, public-access prevention, signed URLs — the
  `ads` pattern). Reference and consent audio are deleted after the key is created (consent audio kept only as
  long as legal review requires). The ~1 MB pack zip is delivered by signed URL.
- **Who and what:** create/replace/delete are **Akun Utama only** (same `requireSuperAdmin` check as the member and
  settings endpoints); the voice, its jobs and its packs are scoped by `business_id` (never from the client).
  Listing and downloading the finished pack is allowed to every member of the business, so staff devices can apply
  "Suara bisnis". One active cloned voice per business.
- **API (all authenticated):** `POST /api/voice-clones` (Akun Utama; multipart: reference, consent) → `202` + job id;
  `GET /api/voice-clones/{id}`; `GET /api/voice-clones/{id}/pack` → signed URL (any member); `DELETE /api/voice-clones/{id}`
  (Akun Utama; "hapus suara saya": key + audio + generated packs).
- **Running the job:** Cloud Run throttles CPU after a response and the service request timeout is 15 s, so the work
  cannot be "fire and forget" in the request. Recommended: **Cloud Tasks → an internal, OIDC-protected endpoint**
  that does the job in one request (the takes are synthesized in parallel, ≈ 10–30 s), with the service timeout raised
  (e.g. `CLOUD_RUN_TIMEOUT=60`). Alternative: a Cloud Run Job per request.
- **Safety by construction:** the server synthesizes **only the fixed number words** (plus the carrier words used
  to read them, see A6b), never user-supplied text, so a cloned voice cannot be made to say anything else. Rate limits: e.g. 3 clones/day per user, 10/day per business,
  and a global ceiling under Google's 10 keys/minute.
- **Clip processing:** the same trim + gain + limiter as `build.sh` (plus the equal-loudness step in A6b), in plain
  Java (no ffmpeg in the image), with golden tests against `build.sh` output. `00_intro` is the user's **own** clip (processed the same way), not
  synthesized; `20_ping` is copied from the default pack.
- **Language:** one `id-ID` key; keep the locale on the profile so other languages are possible later.

### A6. Voice detection ("is there a human voice?")

Layered, cheapest first: (1) **on-device VAD** (e.g. a small Silero/WebRTC model) gates the UI and avoids uploading
music; (2) a **server re-check** (VAD, or Cloud Speech-to-Text only when unsure — about $0.016/min, negligible at
10 s); (3) Google's own rejection is the last line. Pick the segment of the intro with the most speech as the
≤ 10 s reference.

### A6b. Making glued clips sound natural (the quality bar)

**Strategy: unchanged.** The announcer keeps playing `intro` + one clip per word, gapless. The pack stays the same
21-key format, and nothing in the announcer, the pack importer or playback changes. Cloning only replaces the
*recording* step: instead of a person reading each word into a microphone, the server generates each word clip in
the merchant's voice. So everything below is about **how the 19 clips are produced and finished**, so that they
behave like clips cut from one person reading the numbers carefully.

**The risk to design against.** If each word is synthesized as its own request, every clip is a complete
standalone utterance: each one *falls* like the end of a sentence, and pace, pitch, loudness and edge silence differ
from word to word. Glued, that is exactly what sounds robotic.

**Techniques** (in order of expected payoff; confirm by listening in Phase 0):
1. **Read as a list, then cut (preferred).** Synthesize the words as one or a few *list* readings with clear pauses
   between items ("satu, dua, tiga, …"), then cut them into clips. One reading means one consistent voice, pace,
   pitch range and loudness, and list prosody is neutral rather than sentence-final: it is how human packs are
   recorded. We control the pauses (punctuation / pause tags), so cut points can be found from the silences,
   refined with word timestamps from Cloud Speech-to-Text (which also proves each word was said correctly).
2. **Suffix-like words need a carrier.** `belas`, `puluh`, `ratus`, `ribu`, `juta`, `milyar` are never spoken alone.
   Read them after a unit word that does not fuse with them ("tiga belas", "tiga puluh", …) and cut after it, using
   the word timestamps because the boundary is blurred.
3. **Best of several takes.** Generate several takes (say 3–5) and keep the best per word by an automatic score:
   transcribed correctly, duration within the expected range for that word, no clipping or noise, pitch close to the
   median of the set. Outliers are dropped. The cost is still cents.
4. **Equal loudness, then the existing limiter.** Normalize every clip to the same loudness, *then* apply the shipped
   +12 dB / limiter step. Level jumps between words are the most audible flaw once glued, and the current per-clip
   drive would amplify them for synthesized audio.
5. **Tight, click-free edges.** The playback is gapless, so each clip's own edge silence *is* the rhythm. Trim to a
   small fixed margin (about 10–30 ms), cut at zero crossings and add 5–10 ms fades. The shipped −45 dB trim may bite
   into soft consonants of synthesized speech, so tune the threshold on real output.
6. **One speaking rate.** Fix `pace` once per voice at an announcer-friendly speed and reject takes whose duration is
   an outlier, so no word is noticeably slower or faster.
7. **One clip per word has to serve every position.** The list-reading tone is the best single compromise (it is the
   same compromise the human packs make); check the worst cases by ear: amounts ending in `ribu`, `puluh`, `belas`,
   and long ones such as `seratus dua puluh tiga ribu empat ratus lima puluh enam`.

**Recording coach (garbage in, garbage out).** The reference decides the result: Google's docs say the model
reproduces microphone quality and works best with ~10 s of clear, expressive speech with natural pauses. In the app:
a noise/clipping check before a take is accepted, "rekam ulang" hints, and a **suggested script with an
announcement cadence** ("Uang masuk, …"), because the style transfers from the reference.

**How we know it is natural.** Test with the real announcer on a fixed set of ~30 amounts (round, awkward, large),
so the test is the product, not a mock-up.
1. **Blind listening** with 5–10 Indonesian speakers comparing: the bundled human voice, isolated-word generation,
   and the chosen technique. Target: clearly better than isolated words and **within one point (of 5) of the human
   voice**.
2. **Intelligibility, automated:** transcribe the glued announcements with Cloud Speech-to-Text (`id-ID`) and compare
   with the expected text: **0 errors** on the set.
3. **Audio checks, automated:** loudness spread between clips under a few dB, no clipping, no clicks at the cut
   points, no clip with an outlier duration.

**If a clip fails the checks:** regenerate just that clip (more takes); if it still fails, fail the job so the
user can retry or keep their current voice. Never mix a default-voice word into a cloned pack.

### A7. Privacy, legal and Play (start early; these gate the release)

- A voice recording is personal data, and under Indonesia's PDP law may count as **biometric (specific) personal
  data** → explicit, separate consent, a stated purpose, a retention period, and deletion on request. Needs a legal
  read before launch.
- Update **Syarat & Ketentuan / Kebijakan Privasi** (voice recording, Google Cloud as processor, storage outside
  Indonesia, deletion) and add a rule against cloning anyone else's voice.
- **Play Console:** Data safety (audio recordings collected and processed by a third party), the new `RECORD_AUDIO`
  permission, and a check against Google Play's AI-generated-content policy (label the pack as a synthetic voice).
- Employees: a business owner must not clone staff voices without that person's own consent (same logic as the
  attendance clauses already in the Terms).

### A8. Risks / open questions

| Risk | Mitigation |
|---|---|
| **Allow-list denied or slow** (blocker) | Ask now, in parallel with the UI work in Part B. If refused, evaluate another provider behind the same interface. |
| Isolated Indonesian words sound unnatural, especially the suffix-like `belas`/`puluh`/`ratus`/`ribu` | Prototype and listen before building anything; carriers and list readings (A6b); tune `pace`. Spell "milyar" as "miliar" for the synthesizer. |
| Consent check stricter than expected (uploaded intros) | Ask Google; worst case, "upload" only supplies the reference and the consent is always spoken live. |
| `v1beta1` changes | One adapter class; contract test against a recorded fixture; alert on 4xx/5xx rate. |
| Misuse (cloning someone else) | Spoken consent, rate limits, fixed vocabulary, audit trail, report/delete path. |
| **Glued clips still sound choppy** (the central quality risk) | List-reading + cutting, best-of-N takes, equal loudness, tight edges (A6b); choose the technique by the blind test in Phase 0. If none is good enough, that is a no-go for this feature, not a reason to change the announcer. |
| Mixed sample rates break gapless playback | Test early; fall back to resampling server-side. |

### A9. Phases (rough engineering estimates)

0. **Access + naturalness bake-off** — request the allow-list; a script that generates the 19 clips from one real
   recording in the three ways (isolated words, list-reading cut into clips, best-of-N), glues them with the real
   announcer logic on a test set of amounts, then runs the blind listening test and the speech-to-text check.
   *2–3 days of work plus Google's lead time. **Go / no-go and "which technique" are decided here.***
1. **Backend `voice` module** — tables, storage, job, endpoints, the chosen generation technique, clip processing,
   automated quality checks, tests. *4–6 days.*
2. **Android** — record/upload, recording coach, VAD, consent, progress, preview, pack import, delete (the announcer
   itself is untouched). *5–7 days.*
3. **Legal + Play paperwork** — in parallel with 1–2. *2–3 days.*
4. **Beta** — a few merchants, watch quality and failure rates, then release. *3–4 days.*

**Done when:** an Akun Utama can record an intro, hear their own voice read an amount aloud **and the blind test and
speech-to-text check from A6b pass**, apply it, and have it work offline on every device of the business; a chime-only intro keeps the default voice; deleting "suaraku" removes the key, audio and packs; and the
Terms, Privacy Policy and Play declarations match.

---

## Part B — A simpler "Suara Uang Masuk"

### B1. The problem (from the current layouts)

| Screen | Size today |
|---|---|
| Profil | 690-line layout, **12 cards** |
| Suara Uang Masuk (`fragment_sound_hub`) | 530 lines, 4 cards, 3 switches, **6 expandable sections** |
| Sound Pack (`fragment_sound_pack`) | 350 lines, 4 cards, 8 buttons, 8 expandable parts |

One page asks the merchant to understand: *Sound Pack*, *Bunyi dari Bejo* vs *Bunyi dari Aplikasi*, *Aplikasi yang
dipantau*, *Kata kunci*, *Optimasi Ijin Akses Notifikasi* (DND, penghemat baterai, akses notifikasi), *Volume
Max*, speed, a per-syllable editor and "Simpan sebagai Kustom". Much of it is jargon and most people only ever
need two things: **pick a voice** and **make sure the sound actually plays**.

### B2. Principles

1. **One screen, one job.** The top answers "is it working?" and "which voice?". Everything else is one tap deeper.
2. **Progressive disclosure.** Rare and advanced controls (speed, keywords, per-clip editor, volume max) live under
   *Lanjutan*; nothing is deleted, only moved.
3. **Plain Indonesian, no internal terms** ("Sound Pack" → "Suara", "Optimasi Ijin Akses" → "Pastikan bunyi selalu
   keluar").
4. **Fix, don't explain.** One health check replaces four permission rows and their guide dialogs.
5. **Good defaults** so a new merchant needs zero taps to get a working announcement.

### B3. Proposed structure

```
Suara Uang Masuk
┌──────────────────────────────────────────┐
│ ✅ Bunyi uang masuk aktif                 │   ← health card: green when notification,
│    (atau)  ⚠️ 2 hal perlu diperbaiki  [Perbaiki]  │     DND, battery, listener all OK;
└──────────────────────────────────────────┘     one "Perbaiki" opens a checklist
┌──────────────────────────────────────────┐
│ Suara                                     │
│ ▶  Iwan                         [Ganti]   │   ← current voice + play; Ganti → voice list
└──────────────────────────────────────────┘     (bundled voices as ▶ cards, + "Suara
┌──────────────────────────────────────────┐      kamu sendiri" from Part A)
│ Bunyikan untuk uang masuk dari             │
│  [on ] QRIS Bejo                          │
│  [on ] Aplikasi lain          Atur ›      │   ← "Atur" (apps + keywords) only when on
└──────────────────────────────────────────┘
  Pengaturan lanjutan ›                          ← speed, volume maks, keywords, per-clip editor,
                                                    custom packs
```

- **Voice list** is a single screen of cards with a ▶ on each (replaces the dropdown + *Terapkan* + simulator
  card); the current simulator becomes "Coba dengar" with an amount field inside the same screen.
- **Health card** reuses the existing permission checks and guide dialogs, but shows them as a checklist with
  one primary button, in the order that matters.
- **Lanjutan** keeps today's features: speed (Perlahan/Normal/Cepat), volume max, monitored apps, keywords, the
  21-clip editor, *Simpan sebagai Kustom*.

### B4. Where every current control goes

| Today | Moves to |
|---|---|
| Pilih Sound Pack dropdown + Terapkan | Suara → *Ganti* (voice list) |
| Simulasi Suara Notifikasi | "Coba dengar" inside the voice list |
| Bunyi dari Bejo (+ upgrade banner) | "QRIS Bejo" switch (banner only when relevant) |
| Bunyi dari Aplikasi | "Aplikasi lain" switch + *Atur* |
| Aplikasi dipantau, Kata kunci | *Atur* under "Aplikasi lain" |
| DND, penghemat baterai, akses notifikasi | Health card checklist |
| Volume Max Saat Bunyi | Pengaturan lanjutan |
| Atur Kecepatan Bunyi | Pengaturan lanjutan |
| Kustomisasi audio (21 clips), Simpan sebagai Kustom | Pengaturan lanjutan |

### B5. The Profil page

Same idea one level up: replace the 12 stacked cards with a short, grouped **list of rows** — *Akun* (profil, kata
sandi, sidik jari), *Bisnis & Tim* (info bisnis, QRIS, produk, tim), *Suara Uang Masuk*, *Iklan Suara*, *Bantuan &
Legal*, *Keluar*. Keep the upgrade prompt as a single dismissible banner, not a permanent card. The same audit
should then be applied to the Business, Team and Ads hubs, so all hubs behave alike.

### B6. How to do it

1. **Review the screens on a device first** (screenshots of Profil, Suara Uang Masuk, Sound Pack, the permission
   guides) and agree the wireframe above or a variant.
2. Build the **health card** and the **voice list**, then move controls behind *Lanjutan* — no behaviour changes,
   only relocation; keep the same prefs and `SoundPackManager` calls.
3. Restyle Profil as grouped rows.
4. Update `DESIGN.md` with the hub pattern, and the README's screen descriptions.

**Check it worked:** a brand-new merchant gets a working announcement with no changes; fewer settings visible above
the fold (target ≤ 5 controls); fewer support questions about "bunyi tidak keluar"; and try it with 3 real
merchants before release.

**Risks:** hiding things can make a power user feel something was removed (keep *Lanjutan* obvious, and keep
every control); permission flows are the fragile part, so test the health card on Xiaomi/Oppo/Samsung battery
settings; keep deep links from notifications and the widget working.

---

## Part C — Edit Profil: confirm before saving

### C1. Today and why it matters now

The save button (`btnSaveProfile`) sends the change immediately (`EditProfileFragment.saveProfile`): three fields
(Nama, Email, Telepon), no confirmation. That used to be harmless. Since email verification, **changing an owner's
email unverifies the account** and a code goes to the *new* address, so a typo means the owner cannot log in again
once the current session ends, and only the admin can help. A confirmation step catches that.

### C2. Behaviour

1. On tap, run today's validation, then **compare with the stored values**.
   - Nothing changed → a short "Tidak ada perubahan" message; no dialog, no request.
2. Otherwise show a Material dialog, **Simpan perubahan?**, listing **only what changes** (before → after):
   `Nama: A → B`, `Email: x → y`, `Telepon: ditambahkan / diubah / dihapus`. Buttons: **Simpan** (primary) and
   **Periksa lagi** (closes the dialog, keeps the form as typed).
3. **Email changed, Akun Utama only:** add an emphasised line with the new address in bold — *"Kode verifikasi akan
   dikirim ke **y**. Kamu harus memverifikasinya sebelum bisa masuk lagi. Pastikan alamatnya benar."* Akun
   Karyawan are exempt from verification, so they never see this line.
4. **Simpan** disables the button (the existing busy state) so a double tap cannot send twice.
5. **After a successful Akun Utama email change**, don't just pop back: offer to enter the code now
   (`VerifyEmailActivity`, code already sent) or later. The backend answer already says a code was sent; a small
   `data.emailVerificationRequired` flag on `PATCH /api/users/me` would make that explicit instead of inferring it.

Draft Indonesian copy: title *Simpan perubahan?*; buttons *Simpan* / *Periksa lagi*; email warning as above;
phone removal *"Nomor telepon akan dihapus."*

### C3. Notes

- Survive rotation (a `DialogFragment`, or recompute the summary on recreate); keep the typed values on cancel.
- Put the "what changed" logic in a small pure function so it can be unit-tested (same approach as `ResetInput`).
- Other save buttons raise the same question (`Edit Bank` matters most: a wrong account number affects payments;
  also `Edit Business`, member and product editors). Decide whether to give them the same pattern or leave them.
- Optional hardening, separate decision: ask for the **password** when changing the email (a stolen session could
  otherwise swap the address).
- Add an analytics event for "confirm shown / confirmed / cancelled".

**Effort:** about 0.5–1 day. **Done when:** no request is sent without the dialog; an unchanged form sends
nothing; the email warning appears for owners and not for staff; cancel keeps the form intact; and the string and
rotation cases are covered by a manual checklist.

---

## Decisions needed before starting

1. **Request the Instant Custom Voice allow-list now** (it has an unknown lead time and gates Part A).
2. Is a **synthetic voice** acceptable for money announcements, i.e. is the quality bar "sounds like me" or "sounds
   exactly like me"? (Decides whether Phase 0 is enough to proceed.)
3. ~~Who may create a cloned voice~~ — **decided: Akun Utama only**, voice shared with the whole business.
4. Part B first, on its own? It needs nothing from Google and makes room for Part A.
5. Voice naturalness: confirm the bar in A6b (blind listening within one point of the human voice, speech-to-text
   with zero errors).
6. Part C: should the same confirm step apply to Edit Bank / Edit Business, and should changing the email also ask
   for the password?
