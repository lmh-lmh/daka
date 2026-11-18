# -*- coding: utf-8 -*-
"""全球代表性地点「最高可用层级」实测：对每个点从 z20 往下试，找到第一个有真实影像的层级。
判据：灰度众数占比 <= 50%（占位图是 94%）。
这是给 App 用的实用结论：用户在某地能放大到哪一级。
"""
import urllib.request, math, io, time
from PIL import Image

SPOTS = [
    ('北京', 116.40, 39.90), ('上海', 121.47, 31.23), ('宜春', 114.38, 27.81),
    ('南昌', 115.89, 28.68), ('赣州', 114.94, 25.85), ('九江', 115.99, 29.71),
    ('上饶', 117.97, 28.45), ('吉安', 114.99, 27.11), ('抚州', 116.36, 27.95),
    ('东京', 139.69, 35.69), ('新加坡', 103.82, 1.35), ('孟买', 72.88, 19.08),
    ('伦敦', -0.13, 51.51), ('巴黎', 2.35, 48.86), ('莫斯科', 37.62, 55.75),
    ('开罗', 31.23, 30.04), ('内罗毕', 36.82, -1.29), ('开普敦', 18.42, -33.92),
    ('纽约', -74.01, 40.71), ('洛杉矶', -118.24, 34.05),
    ('墨西哥城', -99.13, 19.43), ('里约热内卢', -43.17, -22.90),
    ('布宜诺斯艾利斯', -58.38, -34.60), ('利马', -77.04, -12.05),
    ('悉尼', 151.20, -33.86), ('奥克兰', 174.76, -36.85),
    ('雷克雅未克', -21.83, 64.15), ('迪拜', 55.27, 25.20),
]


def tile_xy(lon, lat, z):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    lat_r = math.radians(lat)
    y = int((1.0 - math.log(math.tan(lat_r) + 1 / math.cos(lat_r)) / math.pi) / 2.0 * n)
    return x, y


def has_imagery(lon, lat, z):
    x, y = tile_xy(lon, lat, z)
    url = ('https://server.arcgisonline.com/ArcGIS/rest/services/'
           'World_Imagery/MapServer/tile/{}/{}/{}').format(z, y, x)
    for attempt in range(3):
        try:
            data = urllib.request.urlopen(url, timeout=25).read()
            break
        except Exception:
            if attempt == 2:
                return None
            time.sleep(2)
    try:
        im = Image.open(io.BytesIO(data)).convert('L')
    except Exception:
        return None
    hist = im.histogram()
    peak = max(range(256), key=lambda i: hist[i])
    return hist[peak] / float(sum(hist)) <= 0.5


print('{:<14} {:<8} {:<28}'.format('地点', '最高级', '各级有影像(z17/z18/z19/z20)'))
summary = []
for name, lon, lat in SPOTS:
    got = {}
    for z in (17, 18, 19, 20):
        got[z] = has_imagery(lon, lat, z)
        time.sleep(0.15)
    ok = [z for z in (17, 18, 19, 20) if got[z]]
    best = max(ok) if ok else '<=16'
    flags = '/'.join('有' if got[z] else '-' for z in (17, 18, 19, 20))
    print('{:<14} {:<8} {:<28}'.format(name, 'z{}'.format(best), flags))
    summary.append((name, best, ok))

print()
from collections import Counter
c = Counter(str(b) for _, b, _ in summary)
print('最高级分布:', dict(c))
print('z18 有影像的:', sum(1 for _, _, ok in summary if 18 in ok), '/', len(summary))
print('z19 有影像的:', sum(1 for _, _, ok in summary if 19 in ok), '/', len(summary))
