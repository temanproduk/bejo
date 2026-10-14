# Voice input ("say the amount")

Saying "dua puluh lima ribu" into the **Nominal** field instead of typing it — on *Catat Transaksi Manual* and on
*Buat QRIS dengan Nominal*. It only ever **fills the field**: the merchant still presses Simpan / Buat QRIS, and can
correct the number first.

## Flow

```
Android: mic tap → AudioRecord (16 kHz mono PCM, stops itself after the merchant finishes speaking)
   → POST /api/voice/amount { audio: base64 PCM, sampleRateHertz }
Bejo:   VoiceAmountService → GoogleSpeechToText (Cloud Speech-to-Text v1, id-ID) → IndonesianAmountParser
   ← { transcript, amount, confidence }
Android: puts `amount` in the field
```

The app never talks to Google directly: there is no key in the app, and the service calls Speech-to-Text **as its own
service account** through Application Default Credentials (the organisation forbids downloadable keys). The
`speech.googleapis.com` API has to be **enabled** on the project (done on `project-68db2e7f-081f-4e2c-b8c`;
`scripts/init-gcp.sh` enables it for a rebuilt project).

> **Not verified yet:** whether the Cloud Run service account may call the API with *no* extra IAM role. It was tried
> only with a user's credentials — impersonating `bejo-cloudrun` to test it was not permitted, and no role was granted
> on a guess. If the first call from production is refused, the log line is `[VOICE] Speech-to-Text answered 403: …`
> with Google's own message; the usual fix is `roles/speech.client` on `bejo-cloudrun`.

## The module

`com.bejo.voice` is self-contained (`ArchitectureTest`: it depends on `shared` only, and nothing depends on it).

| Class | Role |
|---|---|
| `VoiceController` | `POST /api/voice/amount`, any signed-in user |
| `VoiceAmountService` | validates the clip, rate-limits, calls the recogniser, applies the confidence guard, parses; Micrometer counter `voice.amount{result}` (`recognised`, `no_amount`, `low_confidence`, `nothing_heard`) |
| `SpeechToText` (port) / `GoogleSpeechToText` | the recogniser. REST, not gRPC: no extra client library in the image. `@Profile("!test")`; the functional tests use `ScriptedSpeechToText` |
| `IndonesianAmountParser` | words and digits → rupiah |

## What was measured against the real API

All of this was run against the live Speech-to-Text API with clips made by macOS's Indonesian voice (Damayanti) — **not**
with real merchants' voices in a real shop, which is still to be tried on a phone.

- **Trailing silence matters.** With the clip ending right on the last word, "dua puluh lima ribu" came back as `25` and
  "satu juta dua ratus ribu" as "satu juta dua ratus". With a second of quiet after the speech both came back whole. So
  `VoiceAmountRecorder` keeps ~1.2 s of silence **in** the clip.
- **Phrase hints.** Boosting the money words made "ceban" be heard at all (without them: nothing) and kept
  "dua setengah juta" from losing "juta".
- **No invented text.** 2 s of silence, 3 s of quiet white noise and 3 s of loud pink noise all came back with *no
  result* (billed 0 s). Speech with no number ("halo apa kabar hari ini") came back as that text — the parser then finds
  no amount.
- **Noise lowers confidence, not (here) correctness.** Clean speech scored 0.92; the same speech over loud noise was
  still heard right ("25.000") at 0.62.
- **The answer is a mix** of digits and words — `25000`, `25.000`, `2,5 juta`, `dua setengah juta`, `goceng` — which is
  why the parser handles all of them.

## Not making up an amount

A wrong amount that looks right is worse than none, so:

1. The recogniser's silence/noise results are empty (above), and nothing is parsed from empty.
2. **Minimum confidence** (`app.voice.min-confidence`, default `0.5`): below it the words are still returned — the
   merchant sees what was heard — but no `amount`. `0` means the recogniser sent no confidence and is not treated as low.
   The 0.5 is a judgement, between the 0.62 seen for loud-noise speech and the "unsure" range below it; it is a setting
   precisely because it has not been tuned on real use.
3. The parser never guesses past what was said: no number, zero, or one too big for the app → no amount. A bare `25`
   stays 25 (it never invents a missing "ribu").
4. The app shows "Terdengar: …" and only fills the field; nothing is submitted for the merchant.

## What the parser understands

Words (`dua puluh lima ribu`), digits (`25000`, `25.000`, `Rp 25.000`), a mix (`2,5 juta`, `20 ribu 500`), abbreviations
(`20rb`, `20k`, `1,5jt`), the "se-" forms (`seribu`, `sejuta`), `setengah`, `koma`, and unknown words ignored ("tolong
catat dua puluh ribu rupiah").

Slang — only where the value is settled, checked against Indonesian sources (Detik, GoodNews from Indonesia, Mamikos):

| Word | Rp | | Word | Rp |
|---|---|---|---|---|
| `seceng`, `ceceng` | 1.000 | | `noban` | 20.000 |
| `noceng` | 2.000 | | `gocap`, `goban` | 50.000 |
| `goceng` | 5.000 | | `gopek` | 500 |
| `ceban` | 10.000 | | | |

A count in front multiplies: `dua ceban` = 20.000. **`cepek` is deliberately not understood** — the sources disagree on
whether it is Rp100 or Rp100.000, so the parser asks again rather than guess.

## Limits and cost

- Clip: 0.3 s – `VOICE_MAX_SECONDS` (10), whole 16-bit samples, base64; checked **before** anything is sent to Google.
- Each user: `VOICE_MAX_REQUESTS_PER_MINUTE` (12) in any rolling minute → 429 (in memory, per instance, like the login
  throttle). A clip rejected as malformed does not use up the allowance.
- **Cost — read before relying on a number.** Google's pricing page, as returned by a web search (the page itself could
  not be opened to confirm), lists the standard models as free for the first 60 minutes a month and $0.006 per 15 s
  after that, with each request rounded **up** to 15 s. But the API's own `totalBilledTime` for a 2.4 s test clip said
  `3s`, not 15 s, so the real granularity is not clear, and which tier `latest_short` falls in is not confirmed. Check the
  billing report after real use. At 12 requests a minute per user the worst case is bounded either way.
- Speech-to-Text failing (API disabled, quota, no credentials, timeout) → **503** "Input suara belum tersedia. Ketik
  nominalnya dulu ya." — typing keeps working.

## Privacy

The audio is held in memory for the duration of the call and **nothing else**: it is not stored, and neither it nor the
transcript is logged (only its length, the confidence and the amount) — a merchant may say more than a number. The
app's Privacy Policy (Pasal 3k and 8) says so. The Play Console *Data safety* form needs "Audio — voice or sound
recordings", collected, shared with a service provider, not stored, optional (nothing is recorded until the merchant
taps the mic).

## Config

`app.voice.*` — `speech-url`, `model` (`latest_short`), `max-seconds`, `timeout-seconds`, `min-confidence`,
`max-requests-per-minute` (env: `VOICE_SPEECH_URL`, `VOICE_MODEL`, `VOICE_MAX_SECONDS`, `VOICE_TIMEOUT_SECONDS`,
`VOICE_MIN_CONFIDENCE`, `VOICE_MAX_REQUESTS_PER_MINUTE`). The defaults are meant to work as they are.

## Tests

`IndonesianAmountParserTest` (54 phrases with an expected amount, 8 that must give none, plus the `cepek`, null and too-big cases),
`VoiceAmountServiceTest` (validation, rate limit, confidence guard), `GoogleSpeechToTextTest` (request shape, results,
errors — against `MockRestServiceServer`), `VoiceApiIntegrationTest` (the endpoint end to end).
