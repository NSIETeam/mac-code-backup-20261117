"""The pdftoppm subset used by Siyue's document pipeline, backed by PDFium.

Optional local dependency: pypdfium2 and Pillow. Production uses Poppler + poppler-data.
Unsupported flags fail explicitly rather than changing crop or scale semantics.
"""
import argparse
from pathlib import Path
import pypdfium2 as pdfium

parser = argparse.ArgumentParser()
parser.add_argument('-f', type=int, default=1)
parser.add_argument('-l', type=int)
parser.add_argument('-singlefile', action='store_true')
parser.add_argument('-r', type=float, default=150)
parser.add_argument('-scale-to', type=int, default=0, dest='scale_to')
parser.add_argument('-x', type=int, default=0)
parser.add_argument('-y', type=int, default=0)
parser.add_argument('-W', type=int, default=0)
parser.add_argument('-H', type=int, default=0)
parser.add_argument('-png', action='store_true')
parser.add_argument('-jpeg', action='store_true')
parser.add_argument('input')
parser.add_argument('prefix')
args = parser.parse_args()
if not (args.png ^ args.jpeg):
    parser.error('Select exactly one of -png or -jpeg')
with pdfium.PdfDocument(args.input) as pdf:
    start, end = args.f, min(args.l or len(pdf), len(pdf))
    if start < 1 or end < start:
        parser.error('Page range is outside this PDF')
    if args.singlefile:
        end = start
    for number in range(start, end + 1):
        page = pdf[number - 1]
        try:
            width, height = page.get_size()
            scale = args.scale_to / max(width, height) if args.scale_to else args.r / 72
            if scale <= 0 or width * height * scale * scale > 100_000_000:
                parser.error('Page dimensions exceed rendering limits')
            bitmap = page.render(scale=scale)
            image = bitmap.to_pil().copy()
            bitmap.close()
            right = min(image.width, args.x + args.W) if args.W else image.width
            bottom = min(image.height, args.y + args.H) if args.H else image.height
            if args.x < 0 or args.y < 0 or right <= args.x or bottom <= args.y:
                parser.error('Crop is outside the page')
            image = image.crop((args.x, args.y, right, bottom))
            suffix = '' if args.singlefile else '-' + str(number).zfill(len(str(len(pdf))))
            target = Path(args.prefix + suffix + ('.png' if args.png else '.jpg'))
            image.save(target, format='PNG' if args.png else 'JPEG')
        finally:
            page.close()
