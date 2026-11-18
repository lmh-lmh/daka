# -*- coding: utf-8 -*-
"""确认宜春 z19 缺图的影响范围：是宜春一小块，还是整个江西/华南都没有 z19？"""
import urllib.request, math, io, time
from PIL import Image

SPOTS = [
    ('南昌', 115.89, 28.68), ('九江', 115.99, 29.71), ('赣州', 114.94, 25.85),
    ('上饶', 117.97, 28.45), ('吉安', 114.99, 27.11), ('抚州', 116.36, 27.95),
    ('萍乡', 113.85, 27.62), ('新余', 114.93, 27.82), ('景德镇', 117.18, 29.27),
    ('鹰潭', 117.07, 28.26), ('长沙', 112.94, 28.23), ('武汉', 114.31, 30.59),
    ('杭州', 120.15, 30.27), ('福州', 119.30, 26.08), ('深圳', 114.06, 22.54),
    ('北京', 116.40, 39.90),
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
    return hist[peak] / float(sum(hist)), 'x', len(data)


print('{:<8} {:<16} {:<16}'.format('城市', 'z18', 'z19'))
for name, lon, lat in SPOTS:
    cells = []
    for z in (18, 19):
        ratio, _, n = probe(lon, lat, z)
        if ratio is None:
            cells.append('ERR')
        else:
            cells.append('{}({}B)'.format('有' if ratio <= 0.5 else '无占位', n))
        time.sleep(0.2)
    print('{:<8} {:<16} {:<16}'.format(name, cells[0], cells[1]))
