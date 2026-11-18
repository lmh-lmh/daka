# -*- coding: utf-8 -*-
"""对几个可疑城市单独复测：下载瓦片、看字节数、看灰度标准差，判断到底是"无影像"还是"影像太均匀"。
排歧：Esri 的占位图是 2521 B 的均匀灰底 + 白字，std 很低；真实影像即使是大片水面/沙漠也可能 std 低，
但字节数会明显大于 2521。
"""
import urllib.request, math, io
from PIL import Image, ImageStat


def tile(lon, lat, z):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    lat_r = math.radians(lat)
    y = int((1.0 - math.log(math.tan(lat_r) + 1 / math.cos(lat_r)) / math.pi) / 2.0 * n)
    return x, y


SPOTS = [('悉尼', 151.20, -33.86), ('开罗', 31.23, 30.04), ('雷克雅未克', -21.83, 64.15),
         ('北京', 116.40, 39.90)]

for name, lon, lat in SPOTS:
    print('=== {} ({}, {}) ==='.format(name, lon, lat))
    for z in (18, 19):
        x, y = tile(lon, lat, z)
        url = ('https://server.arcgisonline.com/ArcGIS/rest/services/'
               'World_Imagery/MapServer/tile/{}/{}/{}').format(z, y, x)
        try:
            data = urllib.request.urlopen(url, timeout=15).read()
            im = Image.open(io.BytesIO(data))
            sd = ImageStat.Stat(im.convert('L')).stddev[0]
            mean = ImageStat.Stat(im.convert('L')).mean[0]
            verdict = '占位图' if (len(data) < 3000 and sd < 10) else ('真实影像(低纹理)' if sd <= 18 else '真实影像')
            print('  z{}: {}B std={:.1f} mean={:.1f} -> {}'.format(z, len(data), sd, mean, verdict))
            # 存一份留证
            im.save('probe_{}_{}.jpg'.format(name.encode('utf-8').hex(), z))
        except Exception as e:
            print('  z{}: ERR {} {}'.format(z, type(e).__name__, str(e)[:50]))
