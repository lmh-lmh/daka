# -*- coding: utf-8 -*-
"""统计 Esri World Imagery 在全球主要城市的实际影像可用层级。
判断依据：瓦片灰度标准差 > 18 视为"有真实影像纹理"，否则视为 Esri 的占位灰图。
"""
import urllib.request, math, io, sys, concurrent.futures
from PIL import Image, ImageStat

def tile(lon, lat, z):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    lat_r = math.radians(lat)
    y = int((1.0 - math.log(math.tan(lat_r) + 1 / math.cos(lat_r)) / math.pi) / 2.0 * n)
    return x, y

CITIES = [
    ('北京', 116.40, 39.90), ('上海', 121.47, 31.23), ('广州', 113.26, 23.13),
    ('成都', 104.07, 30.57), ('乌鲁木齐', 87.62, 43.82), ('拉萨', 91.14, 29.65),
    ('东京', 139.69, 35.68), ('首尔', 126.98, 37.57), ('曼谷', 100.50, 13.75),
    ('新加坡', 103.82, 1.35), ('新德里', 77.21, 28.61), ('迪拜', 55.27, 25.20),
    ('伊斯坦布尔', 28.98, 41.01), ('莫斯科', 37.62, 55.75), ('巴黎', 2.35, 48.85),
    ('伦敦', -0.13, 51.51), ('罗马', 12.50, 41.90), ('纽约', -74.00, 40.71),
    ('洛杉矶', -118.24, 34.05), ('芝加哥', -87.63, 41.88), ('墨西哥城', -99.13, 19.43),
    ('圣保罗', -46.63, -23.55), ('布宜诺斯艾利斯', -58.38, -34.60),
    ('开罗', 31.23, 30.04), ('内罗毕', 36.82, -1.29), ('开普敦', 18.42, -33.92),
    ('悉尼', 151.20, -33.86), ('奥克兰', 174.76, -36.85),
    ('雷克雅未克', -21.83, 64.15), ('安克雷奇', -149.90, 61.22),
]

def probe(arg):
    name, lon, lat, z = arg
    x, y = tile(lon, lat, z)
    url = ('https://server.arcgisonline.com/ArcGIS/rest/services/'
           'World_Imagery/MapServer/tile/{}/{}/{}').format(z, y, x)
    try:
        data = urllib.request.urlopen(url, timeout=12).read()
        sd = ImageStat.Stat(Image.open(io.BytesIO(data)).convert('L')).stddev[0]
        return (name, z, sd > 18, round(sd, 1))
    except Exception as e:
        return (name, z, None, str(e)[:40])

jobs = [(n, lo, la, z) for n, lo, la in CITIES for z in (17, 18, 19)]
res = {}
with concurrent.futures.ThreadPoolExecutor(max_workers=20) as ex:
    for name, z, ok, extra in ex.map(probe, jobs):
        res.setdefault(z, []).append((name, ok, extra))

for z in sorted(res):
    good = [n for n, o, _ in res[z] if o]
    bad = [n for n, o, _ in res[z] if o is False]
    err = [n for n, o, _ in res[z] if o is None]
    print('z{}: 有高清纹理 {}/{}'.format(z, len(good), len(CITIES)))
    print('   无高清(z19为正常): {}'.format(','.join(bad) if bad else '无'))
    if err:
        print('   网络失败: {}'.format(','.join(err)))
