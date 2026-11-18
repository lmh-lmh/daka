# -*- coding: utf-8 -*-
"""存下宜春 z18 / z19 两块瓦片做证据，肉眼确认 z19 是不是灰块占位图。"""
import urllib.request, math

YICHUN = (114.38, 27.81)


def tile_xy(lon, lat, z):
    n = 2 ** z
    x = int((lon + 180.0) / 360.0 * n)
    lat_r = math.radians(lat)
    y = int((1.0 - math.log(math.tan(lat_r) + 1 / math.cos(lat_r)) / math.pi) / 2.0 * n)
    return x, y


for z in (17, 18, 19, 20):
    x, y = tile_xy(*YICHUN, z)
    url = ('https://server.arcgisonline.com/ArcGIS/rest/services/'
           'World_Imagery/MapServer/tile/{}/{}/{}').format(z, y, x)
    data = urllib.request.urlopen(url, timeout=25).read()
    fn = 'yichun_z{}.jpg'.format(z)
    open(fn, 'wb').write(data)
    print('{} : {}B  tile={}/{}/{}'.format(fn, len(data), z, x, y))
