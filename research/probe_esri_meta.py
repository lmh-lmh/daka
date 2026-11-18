# -*- coding: utf-8 -*-
"""查 Esri World_Imagery 服务元数据：maxLOD / tileInfo / 版权与使用条款字段。"""
import urllib.request, json

url = 'https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer?f=json'
req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
d = json.loads(urllib.request.urlopen(req, timeout=25).read().decode('utf-8'))

print('serviceDescription:', d.get('serviceDescription'))
print('mapName:', d.get('mapName'))
print('maxRecordCount:', d.get('maxRecordCount'))
print('copyrightText:', (d.get('copyrightText') or '')[:400])
ti = d.get('tileInfo') or {}
print('tileInfo rows/cols:', ti.get('rows'), ti.get('cols'))
print('origin:', ti.get('origin'))
lods = ti.get('lods') or []
print('LOD 数量:', len(lods))
print('前3级:', [(l.get('level'), round(l.get('resolution', 0), 4)) for l in lods[:3]])
print('后5级:', [(l.get('level'), round(l.get('resolution', 0), 4)) for l in lods[-5:]])
print('fullExtent:', json.dumps(d.get('fullExtent'), ensure_ascii=False)[:300])
