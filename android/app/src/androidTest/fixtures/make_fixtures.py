#!/usr/bin/env python3
"""Writes the androidTest image fixtures (contract §14.7 metadata checks on real decoders).

gps_orient6.jpg  3000x2000 JPEG: APP1 EXIF (Orientation 6, GPS IFD with lat/long, IFD1 with an
                 embedded blue JPEG thumbnail), APP1 XMP (incl. an Ultra HDR hdrgm block), APP2 MPF,
                 APP13 IPTC, COM. A red block marks the stored top-left corner.
text_alpha.png   600x400 RGBA PNG with tEXt, zTXt, iTXt (XMP), iCCP (sRGB profile) and eXIf (GPS).
Run: python3 make_fixtures.py <assets dir>
"""
import io, struct, sys, zlib
from PIL import Image, PngImagePlugin

out = sys.argv[1]

def seg(marker, data):
    return b"\xff" + bytes([marker]) + struct.pack(">H", len(data) + 2) + data

def exif(orientation, thumb):
    t = io.BytesIO()
    w16 = lambda v: t.write(struct.pack(">H", v))
    w32 = lambda v: t.write(struct.pack(">I", v))
    def entry(tag, typ, count, value, short=False):
        w16(tag); w16(typ); w32(count)
        if short: w16(value); w16(0)
        else: w32(value)
    t.write(b"MM"); w16(42); w32(8)
    # IFD0 @8 (3 entries: 2+36+4=42) -> GPS IFD @50 (5 entries: 2+60+4=66) -> data @116 -> IFD1 -> thumb
    gps_ifd = 50
    data = gps_ifd + 66
    lat, lon = data, data + 24
    ifd1 = lon + 24
    thumb_at = ifd1 + 2 + 24 + 4
    w16(3); entry(0x0112, 3, 1, orientation, short=True); entry(0x8825, 4, 1, gps_ifd); entry(0x0131, 2, 4, 0x52495349); w32(ifd1)
    w16(5)
    entry(0x0001, 2, 2, ord("N") << 24); entry(0x0002, 5, 3, lat)
    entry(0x0003, 2, 2, ord("E") << 24); entry(0x0004, 5, 3, lon)
    entry(0x0000, 1, 4, 0x02030000)
    w32(0)
    for v in (6, 1, 55, 1, 3756, 100): w32(v)    # 6° 55' 37.56"
    for v in (79, 1, 51, 1, 4032, 100): w32(v)   # 79° 51' 40.32"
    w16(2); entry(0x0201, 4, 1, thumb_at); entry(0x0202, 4, 1, len(thumb)); w32(0)
    t.write(thumb)
    return b"Exif\x00\x00" + t.getvalue()

def jpeg(img, q=90):
    b = io.BytesIO(); img.save(b, "JPEG", quality=q); return b.getvalue()

# The embedded EXIF thumbnail is solid blue: a pipeline that reused it would be caught.
thumb = jpeg(Image.new("RGB", (160, 120), (0, 0, 255)), 80)
img = Image.new("RGB", (3000, 2000))
px = img.load()
for y in range(0, 2000, 4):
    for x in range(0, 3000, 4):
        c = (40 + x * 150 // 3000, 120, 40 + y * 150 // 2000)
        for dy in range(4):
            for dx in range(4): px[x + dx, y + dy] = c
for y in range(0, 300):
    for x in range(0, 300): px[x, y] = (255, 0, 0)
base = jpeg(img)
xmp = (b"http://ns.adobe.com/xap/1.0/\x00<x:xmpmeta xmlns:x='adobe:ns:meta/'><rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
       b"<rdf:Description xmlns:hdrgm='http://ns.adobe.com/hdr-gain-map/1.0/' hdrgm:Version='1.0' xmlns:exif='http://ns.adobe.com/exif/1.0/' exif:GPSLatitude='6,55.626N'/></rdf:RDF></x:xmpmeta>")
mpf = b"MPF\x00MM\x00*\x00\x00\x00\x08\x00\x01\xb0\x00\x00\x07\x00\x00\x00\x040100"
iptc = b"Photoshop 3.0\x008BIM\x04\x04\x00\x00\x00\x00\x00\x0c\x1c\x02\x5a\x00\x07Colombo"
gps_jpeg = base[:2] + seg(0xE1, exif(6, thumb)) + seg(0xE1, xmp) + seg(0xE2, mpf) + seg(0xED, iptc) + seg(0xFE, b"shot near Colombo") + base[2:]
open(f"{out}/gps_orient6.jpg", "wb").write(gps_jpeg)

rgba = Image.new("RGBA", (600, 400), (0, 128, 255, 255))
p2 = rgba.load()
for y in range(400):
    for x in range(300): p2[x, y] = (255, 200, 0, 90)
info = PngImagePlugin.PngInfo()
info.add_text("Comment", "GPS 6.9271N 79.8612E")
info.add_text("Author", "camera", zip=True)
info.add_itxt("XML:com.adobe.xmp", xmp[29:].decode("latin-1"))
b = io.BytesIO()
rgba.save(b, "PNG", pnginfo=info, icc_profile=open("/usr/share/color/icc/colord/sRGB.icc", "rb").read())
png = b.getvalue()
ex = exif(1, b"")[6:]
chunk = struct.pack(">I", len(ex)) + b"eXIf" + ex + struct.pack(">I", zlib.crc32(b"eXIf" + ex) & 0xffffffff)
ihdr_end = 8 + 12 + 13
open(f"{out}/text_alpha.png", "wb").write(png[:ihdr_end] + chunk + png[ihdr_end:])
print("fixtures written")
