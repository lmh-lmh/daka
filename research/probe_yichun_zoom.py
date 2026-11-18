# -*- coding: utf-8 -*-
"""宜春各层级 × 各源对照表：z15~z19，看哪个源在哪一级还有影像。"""
import urllib.request, math, io, time
from PIL import Image

YICHUN = (114.38, 27.81)


def tile_xy(lon, lat, z):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    lat_r = math.radians(lat)
    y = int((1.0 - math.log(math.tan(lat_r) + 1 / math.cos(lat_r)) / math.pi) / 2.0 * n)
    return x, y


def wgs2gcj(lon, lat):
    a, ee = 6378245.0, 0.00669342162296594323

    def _tf_lat(x, y):
        r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
        r += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
        r += (20.0 * math.sin(y * math.pi) + 40.0 * math.sin(y / 3.0 * math.pi)) * 2.0 / 3.0
        r += (160.0 * math.sin(y / 12.0 * math.pi) + 320 * math.sin(y * math.pi / 30.0)) * 2.0 / 3.0
        return r

    def _tf_lon(x, y):
        r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
        r += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
        r += (20.0 * math.sin(x * math.pi) + 40.0 * math.sin(x / 3.0 * math.pi)) * 2.0 / 3.0
        r += (150.0 * math.sin(x / 12.0 * math.pi) + 300.0 * math.sin(x / 30.0 * math.pi)) * 2.0 / 3.0
        return r

    dlat = _tf_lat(lon - 105.0, lat - 35.0)
    dlon = _tf_lon(lon - 105.0, lat - 35.0)
    radlat = lat / 180.0 * math.pi
    magic = 1 - ee * math.sin(radlat) ** 2
    sqrtmagic = math.sqrt(magic)
    dlat = (dlat * 180.0) / ((a * (1 - ee)) / (magic * sqrtmagic) * math.pi)
    dlon = (dlon * 180.0) / (a / sqrtmagic * math.cos(radlat) * math.pi)
    return lon + dlon, lat + dlat


def probe(url, timeout=15):
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0', 'Referer': 'https://www.amap.com/'})
        data = urllib.request.urlopen(req, timeout=timeout).read()
    except Exception as e:
        return 'ERR'
    try:
        im = Image.open(io.BytesIO(data)).convert('L')
    except Exception:
        return 'NOTIMG'
    hist = im.histogram()
    peak = max(range(256), key=lambda i: hist[i])
    ratio = hist[peak] / float(sum(hist))
    if ratio > 0.9:
        return '纯色'
    return 'OK' if ratio <= 0.5 else '低纹理'


glon, glat = wgs2gcj(*YICHUN)
print('{:<4} {:>22} {:>22} {:>22}'.format('z', 'Esri(WGS)', '高德(GCJ)', '腾讯(GCJ)'))
for z in (15, 16, 17, 18, 19):
    xw, yw = tile_xy(*YICHUN, z)
    xg, yg = tile_xy(glon, glat, z)
    r_esri = probe('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{}/{}/{}'.format(z, yw, xw))
    r_amap = probe('https://webst01.is.autonavi.com/appmaptile?style=6&x={}&y={}&z={}'.format(xg, yg, z))
    r_tx = probe('https://p1.map.gtimg.com/sateTiles/{}/{}/{}/{}_{}.jpg'.format(z, xg // 16, yg // 16, xg, yg))
    print('{:<4} {:>22} {:>22} {:>22}'.format(z, r_esri, r_amap, r_tx))
    time.sleep(0.3)
