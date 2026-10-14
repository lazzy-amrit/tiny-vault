# Taste
- Verify target versions / API compatibility before implementing; if the specified version doesn't exist, explain the incompatibility rather than silently targeting another version. Confidence: 0.95
- Strongly prefers minimal, single-purpose implementations: no scope creep (no commands, GUIs, metrics, databases, or unrelated extras), small reliable code, only source + build config + metadata + concise setup instructions. Confidence: 0.9
- Security-conscious: credentials/secrets belong in a private config file and must never be logged. Confidence: 0.85
- Prefers fail-safe data-handling patterns: verify new artifacts (e.g. upload + checksum) before replacing or deleting existing good ones, never delete the only valid copy before its replacement is confirmed, preserve prior state on failure, and update state files atomically. Confidence: 0.85
- Prefers essential-only logging (startup, success, failure) and minimal resource/temp-file footprint. Confidence: 0.75
- Prefers fail-safe data-handling patterns: verify new artifacts (e.g. upload + checksum) before replacing or deleting existing good ones, preserve prior state on failure, and update state files atomically. Confidence: 0.8
- Prefers essential-only logging (startup, success, failure) and minimal resource/temp-file footprint. Confidence: 0.75
