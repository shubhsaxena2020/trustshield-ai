# UI direction decision

## Selected direction

Use **Civic Clarity** as the app-wide design foundation, with the **Signal Grid** result evidence rail and restrained risk gauge as a limited treatment on the Check/Result journey.

## Why

TrustShield helps people make a safe next decision under pressure. Civic Clarity gives verdicts, labels and ordered next actions the clearest hierarchy, and its public-service tone avoids making a safety warning feel playful or overly technical. Its blue/slate/mist palette supports high contrast in both themes and leaves green, amber and red for labeled risk states. This is a better fit for sustained reading and Hindi/English/Hinglish than a dark-first console or a soft, low-contrast look.

Signal Grid contributes only where instrumentation is useful: a visible score arc and a narrow evidence rail make the result scannable while keeping confidence distinct from risk. The arc is a summary cue, never the only verdict, and it does not animate through artificial count-up values. Call Shield remains calm and readable rather than inheriting a full console aesthetic.

## Implementation decisions

- Material 3 surfaces and a system-sans type scale; system font fallback handles Devanagari. No downloaded fonts.
- Light surfaces use mist and white with civic navy/slate. Dark surfaces use deep navy with accessible high-contrast text and adjusted risk tones.
- Use green/amber/red with icon and text labels; do not rely on hue alone.
- Use one persistent five-tab navigation bar and a compact app bar. Result is reached from Check and retains Back behavior.
- Keep the paste/check action prominent, evidence verbatim, confidence separate, and next steps ordered.
- Use reduced transitions for navigation and state; disable optional animation at system animator scale zero.
- Treat the six boards as direction studies. Their mock copy and browser-only styling are not production UI content.
