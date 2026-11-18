# -*- coding: utf-8 -*-
"""用校准后的判据（灰度众数占比）重测 30 个陆地城市在 z17/z18/z19 的 Esri 影像可用性。

判据来源（research/probe_calibrate.py）：
  Esri 占位图 esri_z20.jpg：std=5.4 mean=204.7 众数=204 众数占比=94.1%
  真实影像 esri_z18.jpg  ：std=48.5 mean=73.8 众数占比=2.5%
  真实影像 esri_z19.jpg  ：std=61.3 mean=86.8 众数占比=2.2%
→ 占位图的特征是「单一灰度占比极高（>50%）+ 字节数很小（~2521B）」。
  低纹理真实影像（沙漠/水面/暗色荒地）众数占比 10~20%，远低于 50%。
旧判据 std>18 会把暗色真实影像误判成无影像，已废弃。
"""
import urllib.request, math, io, sys, time
from PIL import Image

CITIES = [
    ('北京', 116.40, 39.90), ('上海', 121.47, 31.23), ('广州', 113.26, 23.13),
    ('宜春', 114.38, 27.81), ('成都', 104.07, 30.57), ('乌鲁木齐', 87.62, 43.79),
    ('拉萨', 91.11, 29.65), ('香港', 114.17, 22.32),
    ('东京', 139.69, 35.69), ('首尔', 126.98, 37.57), ('新加坡', 103.82, 1.35),
    ('孟买', 72.88, 19.08), ('迪拜', 55.27, 25.20), ('伊斯坦布尔', 28.98, 41.01),
    ('莫斯科', 37.62, 55.75), ('伦敦', -0.13, 51.51), ('巴黎', 2.35, 48.86),
    ('罗马', 12.50, 41.90), ('雷克雅未克', -21.83, 64.15), ('开罗', 31.23, 30.04),
    ('内罗毕', 36.82, -1.29), ('开普敦', 18.42, -33.92), ('拉各斯', 3.38, 6.52),
    ('纽约', -74.01, 40.71), ('洛杉矶', -118.24, 34.05), ('墨西哥城', -99.13, 19.43),
    ('里约热内卢', -43.17, -22.90), ('布宜诺斯艾利斯', -58.38, -34.60),
    ('利马', -77.04, -12.05), ('悉尼', 151.20, -33.86), ('奥克兰', 174.76, -36.85),
    ('火奴鲁鲁', -157.86, 21.31),
]

ZOOMS = (17, 18, 19)


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
    try:
        data = urllib.request.urlopen(url, timeout=20).read()
    except Exception as e:
        return None, 'ERR:{}'.format(type(e).__name__), 0
    try:
        im = Image.open(io.BytesIO(data)).convert('L')
    except Exception:
        return None, 'NOTIMAGE', len(data)
    hist = im.histogram()
    peak = max(range(256), key=lambda i: hist[i])
    ratio = hist[peak] / float(sum(hist))
    ok = ratio <= 0.5
    return ratio, ('OK' if ok else 'PLACEHOLDER'), len(data)


results = {}
for name, lon, lat in CITIES:
    row = {}
    for z in ZOOMS:
        ratio, verdict, n = probe(lon, lat, z)
        row[z] = (ratio, verdict, n)
        time.sleep(0.15)
    results[name] = row
    line = '{:<14}'.format(name)
    for z in ZOOMS:
        ratio, verdict, n = row[z]
        if ratio is None:
            line += ' z{}={}'.format(z, verdict)
        else:
            line += ' z{}={}({:.0%},{})'.format(z, verdict, ratio, n)
    print(line)
    sys.stdout.flush()

print()
print('=== 汇总（判据：众数占比 <= 50% 视为有真实影像）===')
for z in ZOOMS:
    ok = sum(1 for n in results if results[n][z][1] == 'OK')
    bad = [n for n in results if results[n][z][1] != 'OK']
    print('z{}: {}/{} 有影像  无影像: {}'.format(z, ok, len(results), ', '.join(bad) if bad else '无'))
