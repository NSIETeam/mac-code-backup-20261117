"""Bounded, local-only normalization before free OCR; the original is retained separately."""
import sys
import warnings
from PIL import Image, ImageOps

Image.MAX_IMAGE_PIXELS = 40_000_000
warnings.simplefilter("error", Image.DecompressionBombWarning)

with Image.open(sys.argv[1]) as source:
    if source.width * source.height > 40_000_000 or getattr(source, "n_frames", 1) != 1:
        raise ValueError("Use a single image of at most 40 megapixels, or upload a PDF")
    image = ImageOps.exif_transpose(source)
    image.thumbnail((3200, 3200), Image.Resampling.LANCZOS)
    if image.mode in ("RGBA", "LA") or "transparency" in image.info:
        rgba = image.convert("RGBA")
        image = Image.new("RGB", rgba.size, "white")
        image.paste(rgba, mask=rgba.getchannel("A"))
    else:
        image = image.convert("RGB")
    image.save(sys.argv[2], "PNG")
