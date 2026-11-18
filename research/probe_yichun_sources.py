# -*- coding: utf-8 -*-
"""江西 z19 缺图，找替代源。测：
1. Esri Clarity (clarity.maptiles.arcgis.com) —— Esri 自己的高清版
2. 高德卫星（GCJ-02，需坐标转换）
3. 腾讯卫星（GCJ-02）
4. 天地图（需 key，无 key 应 418）
5. Google（大概率不通）
对每个源取宜春 z19 瓦片，看字节数 + 众数占比判是否占位图。
"""
import urllib.request, math, io, time
from PIL import Image

YICHUN = (114.38, 27.81)


def tile_xy_wgs(lon, lat, z):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    lat_r = math.radians(lat)
    y = int((1.0 - math.log(math.tan(lat_r) + 1 / math.cos(lat_r)) / math.pi) / 2.0 * n)
    return x, y


# WGS84 -> GCJ02
def wgs2gcj(lon, lat):
    a = 6378245.0
    ee = 0.00669342162296594323

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
    magic = math.sin(radlat)
    magic = 1 - ee * magic * magic
    sqrtmagic = math.sqrt(magic)
    dlat = (dlat * 180.0) / ((a * (1 - ee)) / (magic * sqrtmagic) * math.pi)
    dlon = (dlon * 180.0) / (a / sqrtmagic * math.cos(radlat) * math.pi)
    return lon + dlon, lat + dlat


def check(name, url, timeout=15):
    try:
        req = urllib.request.Request(url, headers={
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36'
        })
        r = urllib.request.urlopen(req, timeout=timeout)
        data = r.read()
        info = '{}B {}'.format(len(data), r.headers.get('Content-Type', '?'))
        try:
            im = Image.open(io.BytesIO(data)).convert('L')
            hist = im.histogram()
            peak = max(range(256), key=lambda i: hist[i])
            ratio = hist[peak] / float(sum(hist))
            info += ' 众数={:.0%} {}'.format(ratio, 'OK' if ratio <= 0.5 else '纯色/占位')
        except Exception:
            info += ' [非图片]'
        print('  {:<22} {}'.format(name, info))
        return info
    except Exception as e:
        print('  {:<22} FAIL {} {}'.format(name, type(e).__name__, str(e)[:45]))
        return None


z = 19
xw, yw = tile_xy_wgs(*YICHUN, z)
glon, glat = wgs2gcj(*YICHUN)
xg, yg = tile_xy_wgs(glon, glat, z)
print('宜春 WGS84 ({}, {}) -> GCJ02 ({:.6f}, {:.6f})'.format(YICHUN[0], YICHUN[1], glon, glat))
print('WGS tile z19 {}/{}/{}   GCJ tile z19 {}/{}/{}\n'.format(z, xw, yw, z, xg, yg))

check('Esri 主源', 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{}/{}/{}'.format(z, yw, xw))
check('Esri Clarity', 'https://clarity.maptiles.arcgis.com/arcgis/rest/services/World_Imagery/MapServer/tile/{}/{}/{}'.format(z, yw, xw))
check('高德卫星(GCJ)', 'https://webst01.is.autonavi.com/appmaptile?style=6&x={}&y={}&z={}'.format(xg, yg, z))
check('腾讯卫星(GCJ)', 'https://p1.map.gtimg.com/sateTiles/{}/{}/{}/{}_{}.jpg'.format(z, xg // 16, yg // 16, xg, yg))
check('天地图(无key)', 'https://t0.tianditu.gov.cn/img_w/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=img&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX={}&TILEROW={}&TILECOL={}&tk='.format(z, yw, xw))
check('Google卫星', 'https://mt1.google.com/vt/lyrs=s&x={}&y={}&z={}'.format(xw, yw, z))
