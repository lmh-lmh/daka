# -*- coding: utf-8 -*-
"""宜春（江西）z19 替代源探测：Esri 在江西全省 z19 缺图，看别的源有没有。
测：EOX Sentinel-2、Esri Clarity(World_Imagery 别的 service)、天地图(无key)、
    ArcGIS World Imagery 的其他 tile 端点、以及 z19 是否可 overzoom 到 z20。
"""
import urllib.request, math, io, time
from PIL import Image

YICHUN = (114.38, 27.81)


def tile_xy(lon, lat, z):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    lat_r = math.radians(lat)
    y = int((1.0 - math.log(math.tan(lat_r) + 1 / math.cos(lat_r)) / math.pi) / 2.0 * n)
    return x, y


def check(name, url, timeout=20):
    try:
        req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0 MapLibre'})
        r = urllib.request.urlopen(req, timeout=timeout)
        data = r.read()
        ctype = r.headers.get('Content-Type', '?')
        info = '200 {}B {}'.format(len(data), ctype)
        try:
            im = Image.open(io.BytesIO(data)).convert('L')
            hist = im.histogram()
            peak = max(range(256), key=lambda i: hist[i])
            ratio = hist[peak] / float(sum(hist))
            info += ' 众数={:.0%} {}'.format(ratio, 'OK' if ratio <= 0.5 else '占位/纯色')
        except Exception as e:
            info += ' [非图片: {}]'.format(type(e).__name__)
        print('  {:<28} {}'.format(name, info))
    except Exception as e:
        print('  {:<28} FAIL {} {}'.format(name, type(e).__name__, str(e)[:40]))


for z in (18, 19):
    x, y = tile_xy(*YICHUN, z)
    print('=== 宜春 z{} tile {}/{}/{} ==='.format(z, z, x, y))
    check('Esri World_Imagery',
          'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{}/{}/{}'.format(z, y, x))
    check('Esri(i0 subdomain)',
          'https://services.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{}/{}/{}'.format(z, y, x))
    check('EOX s2cloudless-2020',
          'https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2020_3857/default/g/{}/{}/{}.jpg'.format(z, y, x))
    # 天地图：无 key 应返回错误
    check('天地图 img_w (无key)',
          'https://t0.tianditu.gov.cn/img_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=img&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={}&TILEROW={}&TILECOL={}&tk='.format(z, y, x))
    time.sleep(0.2)
    print()
