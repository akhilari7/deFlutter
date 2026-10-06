"""
Generate high-fidelity legacy and round Android launcher icon PNGs for all densities.
Densities:
  - mdpi: 48x48
  - hdpi: 72x72
  - xhdpi: 96x96
  - xxhdpi: 144x144
  - xxxhdpi: 192x192
"""
import os
from PIL import Image, ImageDraw

DENSITIES = {
    "mipmap-mdpi": 48,
    "mipmap-hdpi": 72,
    "mipmap-xhdpi": 96,
    "mipmap-xxhdpi": 144,
    "mipmap-xxxhdpi": 192,
}

BASE_RES_DIR = r"C:\FlutterInspect\app\src\main\res"

def draw_deflutter_icon(size: int, is_round: bool = False) -> Image.Image:
    # Render at 4x for high-quality antialiasing
    scale = 4
    render_size = size * scale
    img = Image.new("RGBA", (render_size, render_size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    # Base coordinates on 512 grid
    def tf(x, y):
        # Apply scaling and slight centering shift (+10 on 512 viewport)
        fx = (x + 10) / 512.0
        fy = y / 512.0
        return (fx * render_size, fy * render_size)

    # Background
    bg_color = (10, 14, 23, 255) # #0A0E17 Dark Slate
    if is_round:
        draw.ellipse([0, 0, render_size - 1, render_size - 1], fill=bg_color)
    else:
        radius = int(render_size * 0.22) # squircle-like rounded rect
        draw.rounded_rectangle([0, 0, render_size - 1, render_size - 1], radius=radius, fill=bg_color)

    # 1. Flutter Upper Wing: Cyber Cyan (#00E5FF)
    cyan_pts = [tf(128, 256), tf(264, 120), tf(376, 120), tf(184, 312)]
    draw.polygon(cyan_pts, fill=(0, 229, 255, 255))

    # 2. Flutter Lower Wing: Electric Purple (#A855F7)
    purple_pts = [tf(220, 348), tf(292, 420), tf(376, 420), tf(258, 302), tf(312, 248), tf(258, 248)]
    draw.polygon(purple_pts, fill=(168, 85, 247, 255))

    # 3. Electric Lightning Bolt Seam (#00E5FF to #A855F7 glow & core)
    # Outer Cyan bolt
    bolt_pts = [tf(330, 154), tf(242, 246), tf(282, 246), tf(204, 334), tf(230, 286), tf(194, 286)]
    draw.polygon(bolt_pts, fill=(0, 229, 255, 255))

    # Inner White hot core
    core_pts = [tf(324, 162), tf(248, 242), tf(278, 242), tf(214, 318), tf(232, 284), tf(204, 284)]
    draw.polygon(core_pts, fill=(255, 255, 255, 240))

    # Downscale using high-quality Lanczos resampling
    final_img = img.resize((size, size), Image.Resampling.LANCZOS)
    return final_img

def main():
    for folder, dim in DENSITIES.items():
        dir_path = os.path.join(BASE_RES_DIR, folder)
        os.makedirs(dir_path, exist_ok=True)

        # Standard icon
        icon_standard = draw_deflutter_icon(dim, is_round=False)
        std_path = os.path.join(dir_path, "ic_launcher.png")
        icon_standard.save(std_path, "PNG")

        # Round icon
        icon_round = draw_deflutter_icon(dim, is_round=True)
        round_path = os.path.join(dir_path, "ic_launcher_round.png")
        icon_round.save(round_path, "PNG")

        print(f"Generated {dim}x{dim} icons for {folder}")

if __name__ == "__main__":
    main()
