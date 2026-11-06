# Browser baselines — 2026-11-05

These are browser prototype screenshots, not Android screenshots. They contain only synthetic fixtures. Native comparison is NOT_RUN while APK packaging is prohibited.

Capture: Chromium 1243, Windows system sans-serif (Segoe UI), dark theme, normal text, populated default fixtures, paused playback, host viewport 1360×1100 CSS px. Preview outer region is 412×858 CSS px (including decorative frame and simulated system bars). Routes were reloaded before each variant; b-player opens the mini-player after the B reload. The placeholder position is simulated and has no audio evidence.

- a-home-412.png: A home
- b-home-412.png: selected B home
- b-player-412.png: B full player
- c-home-412.png: C home

Additional manual browser walkthrough: B at 320 px and 200% text; played a recent fixture in one tap, switched to search, entered “Тихий берег”, opened full player and queue. No console errors/warnings were reported in that browser session. This is a formative walkthrough, not a complete accessibility review or task-timing study.

The lab footer was updated after capture to record the user choice; it lies outside the captured preview and does not affect these images.

Source file SHA-256 at capture:

- design/ui-prototype/app.js: 7847aba45fa4aa17c5a6050ac920954d4210249fab5678bad0ba41429eee0350
- design/ui-prototype/styles.css: 47e312ba9d6d5ed942b190b7fa83488dcb8990cf2ee8f87ba9d2bc97e9104f7c
- design/ui-prototype/tokens.css: d9370e3abcf43bb6d8ce6ce5b9567b679873f97c8f52e14399cb8508b4d9b0ec
- design/design-tokens.json: d1267ec308dc737b31b5adceaecede46033627ceb29c89a666aa355d1af06a10


Intentional native adaptations require documentation in docs/ui/NATIVE_IMPLEMENTATION.md. Colors are checked from the generated token source; actual Android geometry, font rendering, system insets and screenshot tolerance remain unverified. Do not treat these references as proof that Compose matches within 2 dp.
