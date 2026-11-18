# -*- coding: utf-8 -*-
"""生成省级边界（用于 point-in-polygon 判断"这个点在哪个省"）。

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

BASE = "https://geo.datav.aliyun.com/areas_v3/bound/100000_full.json"
OUT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..",
                                   "新工程", "app", "src", "main", "assets", "admin"))

# 简化阈值（度）。0.01 度约 1.1 km，对"判在哪个省"这个粒度绰绰有余。
TOLERANCE = 0.01
# 每块最多保留的点数，超出则加大阈值重试（防止某个多边形特别复杂）
MAX_POINTS_PER_RING = 1200


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


def main():
    os.makedirs(OUT, exist_ok=True)
    print("拉取省级边界...")
    req = urllib.request.Request(BASE, headers={"User-Agent": "DakaBuild/1.0"})
    d = json.loads(urllib.request.urlopen(req, timeout=60).read())

    units = []
    total_pts = 0
    for f in d.get("features", []):
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
            # GCJ -> WGS，同时压到 5 位小数（约 1 米精度，够用且显著减小体积）
            conv = []
            for pt in ring:
                if not isinstance(pt, (list, tuple)) or len(pt) < 2:
                    continue
                wl, wa = gcj2wgs(float(pt[0]), float(pt[1]))
                conv.append([round(wl, 5), round(wa, 5)])
            if len(conv) < 3:
                continue
            # 逐级加大阈值，直到点数达标
            tol = TOLERANCE
            simp = simplify(conv, tol)
            while len(simp) > MAX_POINTS_PER_RING and tol < 0.5:
                tol *= 2
                simp = simplify(conv, tol)
            if len(simp) >= 3:
                polys.append(simp)
                total_pts += len(simp)
        if polys:
            units.append({"code": code, "name": name, "polys": polys})

    obj = {
        "generated": time.strftime("%Y-%m-%d"),
        "source": "aliyun DataV 100000_full (GCJ-02) -> WGS-84, Douglas-Peucker simplified",
        "tolerance": TOLERANCE,
        "count": len(units),
        "units": units,
    }
    path = os.path.join(OUT, "province_bounds.json")
    with open(path, "w", encoding="utf-8") as fp:
        json.dump(obj, fp, ensure_ascii=False, separators=(",", ":"))

    size = os.path.getsize(path)
    print("省级 {} 条，总坐标点 {} 个".format(len(units), total_pts))
    print("体积 {} B ({} KB)".format(size, round(size / 1024, 1)))

    # 自检
    print("\n自检：丰城打卡点 (115.4064, 28.2324) 应落在江西省内")
    target = (115.4064, 28.2324)
    for u in units:
        if u["name"] == "江西省":
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
            hit = any(in_ring(r, target[0], target[1]) for r in u["polys"])
            print("  结果:", "命中江西" if hit else "未命中（有问题）")


if __name__ == "__main__":
    main()
