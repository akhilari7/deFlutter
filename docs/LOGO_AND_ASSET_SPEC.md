# Deflutter Asset & Logo Specification

**Document Purpose**: Specification guide for designing and implementing a unified, consistent brand identity and logo across the **Deflutter Android Manager App** and the **Web Terminal**.

---

## 1. Brand Identity & Visual Concept

### Name
* **Full Name**: Deflutter
* **Subtitle**: Dart AOT Runtime Dissector & Execution Tracer
* **Tagline**: *Piercing the opaque veil of compiled Flutter binaries.*

### Design Theme
* **Vibe**: Modern offensive security, surgical reverse engineering, high-tech telemetry.
* **Symbolism Ideas**:
  * **The "Unveiling / Slicing" Motif**: A geometric Flutter wing or shield being surgically sliced or illuminated by a lightning pulse or laser beam.
  * **The Dynamic Pulse / Tracer**: An energetic lightning bolt or circuit trace passing through an abstract Dart/Flutter geometric node.
  * **The Lens / Prism**: An optical prism splitting an opaque dark block into cyan and purple trace beams.
* **Constraints**:
  * Must be clean and recognizable as a compact glyph at small sizes (16×16 favicon, 36×36 in-app bar, 48×48 app drawer).
  * Must look striking against dark backgrounds (`#0A0E17`).

### Color Palette

| Color Role | Name | Hex Code | Usage |
| :--- | :--- | :--- | :--- |
| **Primary Accent** | Cyber Cyan | `#00E5FF` | Core glyph glow, outlines, brand focus |
| **Secondary Accent** | Electric Purple | `#A855F7` | Depth, highlights, gradient accents |
| **Success / Live** | Emerald Green | `#10B981` | Real-time active connection indicator |
| **Dark Background** | Slate Dark | `#0A0E17` | Launcher icon background, main UI background |
| **Surface Dark** | Surface Navy | `#131B2E` | Container cards, icon badge backdrop |
| **Foreground Text** | Crisp White | `#F8FAFC` | Primary typography |

---

## 2. Asset Locations & Technical Requirements

### A. Android Companion App Assets

#### 1. Adaptive Launcher Icons (Android 8.0+)
Located in: [`app/src/main/res/`](file:///C:/FlutterInspect/app/src/main/res/)
* **`mipmap-anydpi-v26/ic_launcher.xml`**: Adaptive icon definition referencing background and foreground.
* **`mipmap-anydpi-v26/ic_launcher_round.xml`**: Round variant of the adaptive icon.
* **`drawable/ic_launcher_background.xml`**: Solid `#0A0E17` or subtle dark radial gradient background.
* **`drawable/ic_launcher_foreground.xml`**: Vector XML representation of the Deflutter logo centered within a 108×108 dp canvas (with a 66 dp safe zone in the center to prevent circular/squircle mask clipping).

#### 2. Legacy Launcher PNG Icons (Fallback)
Used on older Android versions and custom third-party launchers:
* `app/src/main/res/mipmap-mdpi/ic_launcher.png` (48×48 px)
* `app/src/main/res/mipmap-hdpi/ic_launcher.png` (72×72 px)
* `app/src/main/res/mipmap-xhdpi/ic_launcher.png` (96×96 px)
* `app/src/main/res/mipmap-xxhdpi/ic_launcher.png` (144×144 px)
* `app/src/main/res/mipmap-xxxhdpi/ic_launcher.png` (192×192 px)
*(Also include matching `ic_launcher_round.png` files for round launcher targets).*

#### 3. In-App Header Logo
Located in: [`app/src/main/java/com/payatu/flutterinspect/ui/components/AppTopBar.kt`](file:///C:/FlutterInspect/app/src/main/java/com/payatu/flutterinspect/ui/components/AppTopBar.kt)
* **Current Placeholder**:
  ```kotlin
  Box(
      modifier = Modifier
          .size(36.dp)
          .clip(RoundedCornerShape(8.dp))
          .background(CyanPrimary.copy(alpha = 0.15f)),
      contentAlignment = Alignment.Center
  ) {
      Icon(
          imageVector = Icons.Default.Security, // <- Replace with logo
          tint = CyanPrimary,
          modifier = Modifier.size(20.dp)
      )
  }
  ```
* **Target Replacement**:
  Create vector drawable `app/src/main/res/drawable/ic_deflutter_logo.xml` and render using:
  ```kotlin
  Icon(
      painter = painterResource(id = R.drawable.ic_deflutter_logo),
      contentDescription = "Deflutter Logo",
      tint = CyanPrimary,
      modifier = Modifier.size(22.dp)
  )
  ```

---

### B. Web Terminal Assets

Located in: [`app/src/main/assets/web/`](file:///C:/FlutterInspect/app/src/main/assets/web/)

#### 1. Browser Favicon
* **Path**: `app/src/main/assets/web/favicon.svg` (and optional `favicon.ico`)
* **Format**: Scalable SVG vector icon with transparent background.
* **HTML Reference**:
  ```html
  <link rel="icon" type="image/svg+xml" href="favicon.svg">
  ```

#### 2. Top Header Brand Icon
Located in: [`app/src/main/assets/web/index.html`](file:///C:/FlutterInspect/app/src/main/assets/web/index.html)
* **Current Placeholder**:
  ```html
  <div class="brand">
      <div class="brand-icon">⚡</div>
      <div>
          <div class="brand-title">DEFLUTTER</div>
          <div class="brand-subtitle">Dart AOT Web Terminal</div>
      </div>
  </div>
  ```
* **Target Replacement**:
  Replace `<div class="brand-icon">⚡</div>` with the SVG logo:
  ```html
  <div class="brand-icon">
      <img src="favicon.svg" alt="Deflutter" style="width: 28px; height: 28px;">
  </div>
  ```

---

## 3. Checklist for Design & Implementation Agent

- [ ] Design high-resolution master vector logo (`deflutter_logo.svg`).
- [ ] Export transparent SVG favicon (`favicon.svg`) to `app/src/main/assets/web/favicon.svg`.
- [ ] Export Android vector drawable (`ic_deflutter_logo.xml`) to `app/src/main/res/drawable/`.
- [ ] Generate adaptive icon assets (`ic_launcher_foreground.xml` and `ic_launcher_background.xml`).
- [ ] Generate standard density PNG icons (`mipmap-mdpi` through `mipmap-xxxhdpi`).
- [ ] Update `AppTopBar.kt` to render `ic_deflutter_logo`.
- [ ] Update `index.html` to link `favicon.svg` and display logo in header.
- [ ] Test build with `./gradlew :app:assembleDebug` and verify appearance on device.
