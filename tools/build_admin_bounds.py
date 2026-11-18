# -*- coding: utf-8 -*-
"""生成行政区边界（用于 point-in-polygon 判断点落在哪个单元）。

为什么需要它：
"最近中心"归类在省界附近会判错，而且误差不小。实测反例：丰城（属宜春市）
的打卡点坐标 115.406,28.232，到新余市中心 66 km、到宜春市中心 111 km，
按"最近中心"会判给新余市 —— 用户在自家门口打卡却显示到邻市，这不能接受。

解法：省级边界只有 34 条、简化后约 6000 个点、几十 KB，完全塞得进 APK。
先用它做 point-in-polygon 精确定省，再在省内用"最近中心"定市（市级边界
475 条太多，暂不做）。

产物：assets/admin/province_bounds.json
     {"generated":..., "units":[{"code","name","polys":[[[lon,lat],...], ...]}, ...]}
     一个省可能有多块（如海南、舟山群岛），所以 polys 是列表的列表。
"""
import json
import math
import os
import time
import urllib.request

PROVINCE_URL = "https://geo.datav.aliyun.com/areas_v3/bound/100000_full.json"
CITY_URL = "https://geo.datav.aliyun.com/areas_v3/bound/{}.json"
OUT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..",
                                   "新工程", "app", "src", "main", "assets", "admin"))

PROVINCE_CODES = [110000,120000,130000,140000,150000,210000,220000,230000,310000,320000,
                  330000,340000,350000,360000,370000,410000,420000,430000,440000,450000,
                  460000,500000,510000,520000,530000,540000,610000,620000,630000,640000,
                  650000,710000,810000,820000]

# 省级：0.01 度（约 1.1 km），判省够用
PROV_TOLERANCE = 0.01
PROV_MAX_POINTS = 1200
# 市级：0.02 度（约 2.2 km）更激进，因为只需判"在哪个市"，且 475 条基数大
CITY_TOLERANCE = 0.02
CITY_MAX_POINTS = 300
# 区县：0.03 度（约 3.3 km），2800 条基数最大，必须压到最小
DIST_TOLERANCE = 0.03
DIST_MAX_POINTS = 160


def gcj2wgs(lon, lat):
    a, ee = 6378245.0, 0.00669342162296594323
    if not (73.66 < lon < 135.05 and 3.86 < lat < 53.55):
        return lon, lat

    def tlat(x, y):
        r = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
        r += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
        r += (20.0 * math.sin(y * math.pi) + 40.0 * math.sin(y / 3.0 * math.pi)) * 2.0 / 3.0
        r += (160.0 * math.sin(y / 12.0 * math.pi) + 320 * math.sin(y * math.pi / 30.0)) * 2.0 / 3.0
        return r

    def tlon(x, y):
        r = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
        r += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
        r += (20.0 * math.sin(x * math.pi) + 40.0 * math.sin(x / 3.0 * math.pi)) * 2.0 / 3.0
        r += (150.0 * math.sin(x / 12.0 * math.pi) + 300.0 * math.sin(x / 30.0 * math.pi)) * 2.0 / 3.0
        return r

    dlat = tlat(lon - 105.0, lat - 35.0)
    dlon = tlon(lon - 105.0, lat - 35.0)
    rl = lat / 180.0 * math.pi
    m = 1 - ee * math.sin(rl) ** 2
    sm = math.sqrt(m)
    dlat = (dlat * 180.0) / ((a * (1 - ee)) / (m * sm) * math.pi)
    dlon = (dlon * 180.0) / (a / sm * math.cos(rl) * math.pi)
    return lon - dlon, lat - dlat


def simplify(ring, tol):
    """Douglas-Peucker，保留首尾，删掉离首尾连线太近的中间点。"""
    if len(ring) <= 3:
        return ring
    keep = [False] * len(ring)
    keep[0] = keep[-1] = True
    stack = [(0, len(ring) - 1)]
    while stack:
        i, j = stack.pop()
        if j <= i + 1:
            continue
        x1, y1 = ring[i]
        x2, y2 = ring[j]
        dx, dy = x2 - x1, y2 - y1
        norm = math.hypot(dx, dy)
        best_i, best_d = -1, -1.0
        for k in range(i + 1, j):
            x, y = ring[k]
            if norm < 1e-12:
                d = math.hypot(x - x1, y - y1)
            else:
                d = abs(dy * x - dx * y + x2 * y1 - y2 * x1) / norm
            if d > best_d:
                best_d, best_i = d, k
        if best_d > tol and best_i > 0:
            keep[best_i] = True
            stack.append((i, best_i))
            stack.append((best_i, j))
    return [ring[i] for i in range(len(ring)) if keep[i]]


def extract_rings(geom):
    """把 Polygon/MultiPolygon 统一摊平成"环列表"，只取外环（内环是湖，判省时不需要）。"""
    t = geom.get("type")
    coords = geom.get("coordinates")
    rings = []
    if t == "Polygon":
        if coords:
            rings.append(coords[0])
    elif t == "MultiPolygon":
        for poly in coords or []:
            if poly:
                rings.append(poly[0])
    return rings


def build_units(features, tol, max_pts):
    """从 FeatureCollection 提取 (code, name, polys)，坐标转 WGS 并简化。"""
    units = []
    total_pts = 0
    for f in features:
        p = f.get("properties", {})
        code = str(p.get("adcode", "")).strip()
        name = (p.get("name") or "").strip()
        if not code or not name or "_" in code:
            continue
        geom = f.get("geometry") or {}
        rings = extract_rings(geom)
        if not rings:
            continue
        polys = []
        for ring in rings:
            conv = []
            for pt in ring:
                if not isinstance(pt, (list, tuple)) or len(pt) < 2:
                    continue
                wl, wa = gcj2wgs(float(pt[0]), float(pt[1]))
                conv.append([round(wl, 5), round(wa, 5)])
            if len(conv) < 3:
                continue
            t = tol
            simp = simplify(conv, t)
            while len(simp) > max_pts and t < 2.0:
                t *= 2
                simp = simplify(conv, t)
            if len(simp) >= 3:
                polys.append(simp)
                total_pts += len(simp)
        if polys:
            units.append({"code": code, "name": name, "polys": polys})
    return units, total_pts


def dump(name, units, total_pts, tol):
    obj = {
        "generated": time.strftime("%Y-%m-%d"),
        "source": "aliyun DataV areas_v3 (GCJ-02) -> WGS-84, Douglas-Peucker simplified",
        "tolerance": tol,
        "count": len(units),
        "units": units,
    }
    path = os.path.join(OUT, name)
    with open(path, "w", encoding="utf-8") as fp:
        json.dump(obj, fp, ensure_ascii=False, separators=(",", ":"))
    size = os.path.getsize(path)
    print("  {} -> {} 条, {} 点, {} KB".format(name, len(units), total_pts, round(size / 1024, 1)))
    return size


def main():
    os.makedirs(OUT, exist_ok=True)

    print("[1/2] 省级边界...")
    req = urllib.request.Request(PROVINCE_URL, headers={"User-Agent": "DakaBuild/1.0"})
    d = json.loads(urllib.request.urlopen(req, timeout=60).read())
    prov, pts = build_units(d.get("features", []), PROV_TOLERANCE, PROV_MAX_POINTS)
    dump("province_bounds.json", prov, pts, PROV_TOLERANCE)

    print("[2/2] 市级边界（34 次请求）...")
    all_cities = []
    for i, pc in enumerate(PROVINCE_CODES, 1):
        try:
            req = urllib.request.Request(CITY_URL.format(str(pc) + "_full"),
                                         headers={"User-Agent": "DakaBuild/1.0"})
            sub = json.loads(urllib.request.urlopen(req, timeout=40).read())
        except Exception as e:
            print("    {} 失败: {}".format(pc, str(e)[:40]))
            continue
        units, _ = build_units(sub.get("features", []), CITY_TOLERANCE, CITY_MAX_POINTS)
        all_cities.extend(units)
        print("  [{:2d}/34] {} 个地市".format(i, len(units)))
        time.sleep(0.08)
    dump("city_bounds.json", all_cities, sum(len(p) for u in all_cities for p in u["polys"]),
         CITY_TOLERANCE)

    print("[3/3] 区县边界（逐个地市请求）...")
    all_dists = []
    failed = []
    for i, c in enumerate(all_cities, 1):
        code = c["code"]
        try:
            req = urllib.request.Request(CITY_URL.format(code + "_full"),
                                         headers={"User-Agent": "DakaBuild/1.0"})
            sub = json.loads(urllib.request.urlopen(req, timeout=30).read())
        except Exception as e:
            failed.append(code)
            continue
        units, _ = build_units(sub.get("features", []), DIST_TOLERANCE, DIST_MAX_POINTS)
        all_dists.extend(units)
        if i % 40 == 0:
            print("  [{}/{}] 已处理 {} 个地市, 区县 {} 条".format(i, len(all_cities), i, len(all_dists)))
        time.sleep(0.05)
    print("  区县失败的地市数:", len(failed))
    dump("district_bounds.json", all_dists,
         sum(len(p) for u in all_dists for p in u["polys"]), DIST_TOLERANCE)

    # 自检：丰城打卡点应落在中国境内、且不在新余市
    print("\n自检：丰城打卡点 (115.4064, 28.2324)")
    def in_ring(ring, x, y):
        inside = False
        n = len(ring)
        j = n - 1
        for i in range(n):
            xi, yi = ring[i]
            xj, yj = ring[j]
            if ((yi > y) != (yj > y)) and (x < (xj - xi) * (y - yi) / (yj - yi) + xi):
                inside = not inside
            j = i
        return inside
    for u in all_cities:
        if any(in_ring(r, 115.4064, 28.2324) for r in u["polys"]):
            print("   命中地市:", u["name"], u["code"])


if __name__ == "__main__":
    main()
