import math, urllib.request, io, os
from PIL import Image

def deg2tile(lon, lat, z):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    lat_r = math.radians(lat)
    y = int((1.0 - math.log(math.tan(lat_r) + 1/math.cos(lat_r)) / math.pi) / 2.0 * n)
    return x, y

pts = [("丰城郊外-截图点", 115.4063, 28.2324),
       ("宜春市中心", 114.4167, 27.8000),
       ("袁州", 114.38, 27.81)]

base = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"
for name, lon, lat in pts:
    for z in (16, 17, 18, 19):
        x, y = deg2tile(lon, lat, z)
        url = base.format(z=z, y=y, x=x)
        try:
            with urllib.request.urlopen(url, timeout=25) as r:
                b = r.read()
            im = Image.open(io.BytesIO(b)).convert("L")
            px = list(im.getdata())
            from collections import Counter
            mode_cnt = Counter(px).most_common(1)[0][1]
            ratio = mode_cnt / len(px) * 100
            print(f"{name:16s} z{z}  x={x} y={y}  {len(b):6d}B  众数占比={ratio:5.1f}%  {'占位' if ratio>50 else '真图'}")
            if z == 18:
                fn = f"research/tile_{name}_z18.jpg".replace("/", "_")
                open(fn, "wb").write(b)
        except Exception as e:
            print(f"{name:16s} z{z}  失败 {e}")
    print()
