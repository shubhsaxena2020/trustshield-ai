# Landing component sources

The landing page uses original TrustShield UI code and locally owned screenshots. Two earlier local projects informed the motion and composition. Their implementation code and assets were not copied.

| Idea rebuilt | Local source reviewed | What was adapted | License status |
| --- | --- | --- | --- |
| Orbiting shield and signal particles | `C:\Users\shubh\Projects\igloo-experience\VOLUMETRIC_SDF_PARTICLE_SYSTEM.md` and `preview.html` | The source's mouse-aware volumetric particle field inspired the shield's orbit rings, floating signal bubbles, and pointer-reactive perspective. The TrustShield scene is rebuilt as original SVG and CSS. | No project-level license file was present. The README describes a reconstruction of the third-party Igloo Inc. site. No source code, models, textures, or assets were copied. |
| Scroll-linked motion and reveal | `C:\Users\shubh\Projects\portfolio-v2\src\components\scroll-marquee.tsx` | Its scroll-scrubbed marquee and section reveal informed lightweight IntersectionObserver reveals and the screen-by-screen product walkthrough. This page uses native browser APIs and CSS transforms, not the source component or its animation packages. | No root license file was present. No source code, fonts, or assets were copied. |

The hero's shield and orbit illustration is `apps/landing/public/shield-static.svg`, rendered with a low-cost CSS perspective tilt on pointer input and a scroll-linked offset. The implementation is self-contained and does not require WebGL or a 3D runtime.

## White-theme contrast checks

Ratios are calculated from the page's sRGB color tokens using the WCAG relative-luminance formula:

| Foreground | Surface | Contrast |
| --- | --- | ---: |
| `#183446` body text | `#FFFAF3` warm paper | 12.49:1 |
| `#526873` supporting text | `#FFFAF3` warm paper | 5.64:1 |
| `#FFFFFF` button label | `#126B69` primary teal | 6.30:1 |
| `#163747` text | `#DFF0E6` privacy surface | 10.62:1 |
