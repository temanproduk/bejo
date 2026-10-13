# Admin dashboard API

The `admin` module backs the static dashboard at `https://dashboard.bejo.temanproduk.com` (its own
repository, `bejo-dashboard`, deployed on Cloudflare Pages). It is read-only: two cross-business lists
behind a passwordless sign-in.

## Who is an admin

An address on `app.admin.emails` (`ADMIN_EMAILS`, comma-separated). That list is the whole definition:
admins have no password and no row in `core.users`, and an app user is never an admin by virtue of their
account. An empty list means nobody can sign in.

## Sign-in (`AdminAuthService`)

1. `POST /api/admin/auth/otp/request {email}` emails an **8-digit** code (valid 10 minutes) through the
   same `EmailSender` as password reset. The answer is identical, and padded to the same duration,
   whether or not the address is an admin. Limits per address: one code per 60 seconds, five per hour.
2. `POST /api/admin/auth/otp/verify {email, code}` returns a session token (12 hours).

The code is stored only as an HMAC keyed by the server secret, works once, and allows five guesses
(spent with an atomic conditional update before the comparison). Every failure answers the same 400.

A session is a random 256-bit bearer token of which only the SHA-256 is stored (`admin.admin_sessions`).
`AdminAuthFilter` looks it up on every `/api/admin/**` request, so sign-out, expiry, or removing the
address from `ADMIN_EMAILS` ends it at once. Sessions are deliberately not JWTs: they share nothing with
the app's user tokens, so neither can be accepted in place of the other (`/api/admin/**` is `permitAll`
in `SecurityConfig`; the filter is its only gate).

## Rules that are easy to break

- **`/api/admin/**` still needs `X-API-TOKEN`.** The dashboard therefore ships that token in its public
  `config.js`. That is acceptable only because the token is not what protects admin data (the emailed
  code is) and it already ships inside the mobile app. Never let an admin route rely on it alone.
- **CORS is open for `/api/admin/**` only**, and only to `app.admin.dashboard-origins`
  (`AdminCorsConfig`). The `CorsFilter` is first in the chain because a preflight cannot carry
  `X-API-TOKEN`; keep it ahead of `ApiTokenFilter`.
- **The list queries are not business-scoped**, which is the point. They live in the admin module's own
  read-only repositories so that core's and commerce's repositories stay business-scoped throughout.
- **No query parameter is null** (an absent filter is `''` or `'%'`): Postgres cannot type a bare
  `:param is null`, and the H2 tests would not catch it.
- **Sign-in codes must never be logged**: `RequestLoggingFilter` skips bodies under `/api/admin/auth/`.
