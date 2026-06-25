# Keep OkHttp / Okio (they ship their own rules, this is belt-and-suspenders)
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# Media3 keeps what it needs via its own consumer rules.
# Nothing in this app relies on reflection, so default shrinking is safe.
