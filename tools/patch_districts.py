# -*- coding: utf-8 -*-
"""补漏：重试那些拉取失败的地市，把缺的区县边界补进 district_bounds.json。

为什么要单独一个脚本：第一次跑的时候 143 个地市 HTTP 失败（网络抖动 / 限流），
重跑整个流程要几分钟；这个脚本只补缺的部分，几十秒搞定。
"""
import json, math, os, time, urllib.request

BASE = "https://geo.datav.aliyun.com/areas_v3/bound/{}.json"
ASSETS = os.path.abspath(os.path.join(os.path.dirname(__file__), "..",
                                      "新工程", "app", "src", "main", "assets", "admin"))
DIST_TOL = 0.03
DIST_MAX_POINTS = 160


def gcj2wgs(lon, lat):
    a, ee = 6378245.0, 0.00669342162296594323
    if not (73.66 < lon < 135.05 and 3.86 < lat < 53.55):
        return lon, lat
    def tlat(x, y):
        r = -100.0+2.0*x+3.0*y+0.2*y*y+0.1*x*y+0.2*math.sqrt(abs(x))
        r += (20.0*math.sin(6.0*x*math.pi)+20.0*math.sin(2.0*x*math.pi))*2.0/3.0
        r += (20.0*math.sin(y*math.pi)+40.0*math.sin(y/3.0*math.pi))*2.0/3.0
        r += (160.0*math.sin(y/12.0*math.pi)+320*math.sin(y*math.pi/30.0))*2.0/3.0
        return r
    def tlon(x, y):
        r = 300.0+x+2.0*y+0.1*x*x+0.1*x*y+0.1*math.sqrt(abs(x))
        r += (20.0*math.sin(6.0*x*math.pi)+20.0*math.sin(2.0*x*math.pi))*2.0/3.0
        r += (20.0*math.sin(x*math.pi)+40.0*math.sin(x/3.0*math.pi))*2.0/3.0
        r += (150.0*math.sin(x/12.0*math.pi)+300.0*math.sin(x/30.0*math.pi))*2.0/3.0
        return r
    dlat, dlon = tlat(lon-105.0, lat-35.0), tlon(lon-105.0, lat-35.0)
    rl = lat/180.0*math.pi; m = 1-ee*math.sin(rl)**2; sm = math.sqrt(m)
    dlat = (dlat*180.0)/((a*(1-ee))/(m*sm)*math.pi)
    dlon = (dlon*180.0)/(a/sm*math.cos(rl)*math.pi)
    return lon-dlon, lat-dlat


def simplify(ring, tol):
    if len(ring) <= 3:
        return ring
    keep = [False]*len(ring); keep[0] = keep[-1] = True
    stack = [(0, len(ring)-1)]
    while stack:
        i, j = stack.pop()
        if j <= i+1: continue
        x1, y1 = ring[i]; x2, y2 = ring[j]
        dx, dy = x2-x1, y2-y1
        norm = math.hypot(dx, dy); bi, bd = -1, -1.0
        for k in range(i+1, j):
            x, y = ring[k]
            d = math.hypot(x-x1, y-y1) if norm < 1e-12 else abs(dy*x-dx*y+x2*y1-y2*x1)/norm
            if d > bd: bd, bi = d, k
        if bd > tol and bi > 0:
            keep[bi] = True
            stack.append((i, bi)); stack.append((bi, j))
    return [ring[i] for i in range(len(ring)) if keep[i]]


def extract_rings(geom):
    t = geom.get("type"); c = geom.get("coordinates")
    out = []
    if t == "Polygon" and c: out.append(c[0])
    elif t == "MultiPolygon":
        for p in c or []:
            if p: out.append(p[0])
    return out


def build(features, tol, maxp):
    units = []
    for f in features:
        p = f.get("properties", {})
        code = str(p.get("adcode", "")).strip()
        name = (p.get("name") or "").strip()
        if not code or not name or "_" in code: continue
        rings = extract_rings(f.get("geometry") or {})
        if not rings: continue
        polys = []
        for ring in rings:
            conv = []
            for pt in ring:
                if not isinstance(pt, (list, tuple)) or len(pt) < 2: continue
                wl, wa = gcj2wgs(float(pt[0]), float(pt[1]))
                conv.append([round(wl, 5), round(wa, 5)])
            if len(conv) < 3: continue
            t = tol; s = simplify(conv, t)
            while len(s) > maxp and t < 2.0:
                t *= 2; s = simplify(conv, t)
            if len(s) >= 3: polys.append(s)
        if polys: units.append({"code": code, "name": name, "polys": polys})
    return units


def main():
    path = os.path.join(ASSETS, "district_bounds.json")
    d = json.load(open(path, encoding="utf-8"))
    have = {u["code"] for u in d["units"]}

    cities = json.load(open(os.path.join(ASSETS, "city_bounds.json"), encoding="utf-8"))
    city_codes = [c["code"] for c in cities["units"]]

    # 哪些市一个区县都没有
    missing = []
    for cc in city_codes:
        if not any(code.startswith(cc[:4]) for code in have):
            missing.append(cc)
    print("需要补的地市:", len(missing))

    added = 0
    for i, cc in enumerate(missing, 1):
        for attempt in range(3):
            try:
                req = urllib.request.Request(BASE.format(cc + "_full"),
                                             headers={"User-Agent": "DakaBuild/1.0"})
                sub = json.loads(urllib.request.urlopen(req, timeout=30).read())
                break
            except Exception:
                if attempt == 2: sub = None
                time.sleep(1.0)
        if not sub: continue
        units = build(sub.get("features", []), DIST_TOL, DIST_MAX_POINTS)
        for u in units:
            if u["code"] not in have:
                d["units"].append(u); have.add(u["code"]); added += 1
        if i % 20 == 0:
            print("  [{}/{}] 已补 {} 条".format(i, len(missing), added))
        time.sleep(0.06)

    d["count"] = len(d["units"])
    with open(path, "w", encoding="utf-8") as fp:
        json.dump(d, fp, ensure_ascii=False, separators=(",", ":"))
    print("补充完成: 新增 {} 条, 总计 {} 条, {} KB".format(
        added, len(d["units"]), os.path.getsize(path)//1024))

    # 校验宜春
    yc = ["360902","360981","360982","360983","360924","360925","360926","360927","360928","360929"]
    have2 = {u["code"] for u in d["units"]}
    print("宜春区县命中:", sum(1 for c in yc if c in have2), "/", len(yc))


if __name__ == "__main__":
    main()
