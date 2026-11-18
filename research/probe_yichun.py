# -*- coding: utf-8 -*-
"""宜春周边 z19 加密探测：确认是单个瓦片偶然，还是整片区域真的缺 z19 影像。"""
import urllib.request, math, io, time
from PIL import Image

CENTERS = [
    ('宜春市中心', 114.38, 27.81),
    ('宜春-袁州', 114.42, 27.80),
    ('宜春-城西', 114.34, 27.81),
    ('宜春-温汤', 114.33, 27.71),
    ('宜春-明月山', 114.28, 27.60),
    ('丰城', 115.77, 28.16),
    ('樟树', 115.55, 28.06),
    ('高安', 115.38, 28.42),
]


def tile_xy(lon, lat, z):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    lat_r = math.radians(lat)
    y = int((1.0 - math.log(math.tan(lat_r) + 1 / math.cos(lat_r)) / math.pi) / 2.0 * n)
    return x, y


def probe(lon, lat, z):
    x, y = tile_xy(lon, lat, z)
    url = ('https://server.arcgisonline.com/ArcGIS/rest/services/'
           'World_Imagery/MapServer/tile/{}/{}/{}').format(z, y, x)
    for attempt in range(3):
        try:
            data = urllib.request.urlopen(url, timeout=25).read()
            break
        except Exception as e:
            if attempt == 2:
                return None, 'ERR:{}'.format(type(e).__name__), 0
            time.sleep(2)
    im = Image.open(io.BytesIO(data)).convert('L')
    hist = im.histogram()
    peak = max(range(256), key=lambda i: hist[i])
    ratio = hist[peak] / float(sum(hist))
    return ratio, ('OK' if ratio <= 0.5 else 'PLACEHOLDER'), len(data)


for z in (18, 19):
    print('=== z{} ==='.format(z))
    for name, lon, lat in CENTERS:
        ratio, verdict, n = probe(lon, lat, z)
        x, y = tile_xy(lon, lat, z)
        if ratio is None:
            print('  {:<12} {:<12} {}'.format(name, verdict, n))
        else:
            print('  {:<12} {:<12} 众数={:.0%} {}B  tile={}/{}/{}'.format(
                name, verdict, ratio, n, z, x, y))
        time.sleep(0.2)
