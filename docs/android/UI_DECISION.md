# Android UI decision

The owner has selected a strictly monochrome TrustShield interface to match the black-and-white logo.

- **System** follows the device appearance and resolves to one of the two app themes below.
- **Black** uses a near-black background (`#050505`), neutral dark-gray surfaces, white text, and white-on-black primary buttons.
- **White** uses a white background, neutral light-gray surfaces, black text, and black-on-white primary buttons.
- Links, navigation selection, banners, buttons, and other interface accents use neutral greys and the theme foreground. Blue accents are removed.
- Green, amber, and red are reserved for low, caution, and high-risk verdicts. Genuine errors may use error colors. Risk text and badge combinations must retain WCAG AA contrast.
- The setting remains persisted and applies in place without recreating the activity. Language switching and bottom navigation remain available.
