# -*- coding: utf-8 -*-
"""快速版构建：只拉省级 + 地市（不逐个拉区县）。

为什么分两个版本：
- 逐个拉 340 个地市的区县要 340 次 HTTP，几十秒到几分钟，且区县这一级在分层视图里
  用得最少（全国 → 省 → 市 三级已经能满足"大致看出去了哪些地方"）。
- 先出省+市两级，立刻能跑通分层；区县等真的需要时再补（脚本保留完整版）。

产物：
    provinces.json  34 条
    cities.json     全国地级市
    districts.json  空表（结构一致，占位，保证 AdminTree 不崩）
"""
import json, math, os, time, urllib.request

BASE = "https://geo.datav.aliyun.com/areas_v3/bound/{}.json"
OUT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..",
                                   "新工程", "app", "src", "main", "assets", "admin"))
PROVINCES = [110000,120000,130000,140000,150000,210000,220000,230000,310000,320000,
             330000,340000,350000,360000,370000,410000,420000,430000,440000,450000,
             460000,500000,510000,520000,530000,540000,610000,620000,630000,640000,
             650000,710000,810000,820000]
MUNI = {110000,120000,310000,500000}

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
    dl, dn = tlat(lon-105.0, lat-35.0), tlon(lon-105.0, lat-35.0)
    rl = lat/180.0*math.pi
    m = 1-ee*math.sin(rl)**2
    sm = math.sqrt(m)
    dl = (dl*180.0)/((a*(1-ee))/(m*sm)*math.pi)
    dn = (dn*180.0)/(a/sm*math.cos(rl)*math.pi)
    return lon-dn, lat-dl

def fetch(code):
    for i in range(3):
        try:
            req = urllib.request.Request(BASE.format(code), headers={"User-Agent":"DakaBuild/1.0"})
            return json.loads(urllib.request.urlopen(req, timeout=25).read())
        except Exception:
            if i == 2: return None
            time.sleep(1.2)

def slim(data, level):
    rows = []
    if not data: return rows
    for f in data.get("features", []):
        p = f.get("properties", {})
        code = str(p.get("adcode","")).strip()
        name = (p.get("name") or "").strip()
        if not code or not name or "_" in code: continue
        c = p.get("center")
        if not c: continue
        wl, wa = gcj2wgs(float(c[0]), float(c[1]))
        item = {"code":code, "name":name,
                "center":[round(wl,6), round(wa,6)],
                "children":int(p.get("childrenNum") or 0)}
        if level != "province":
            item["parent"] = str((p.get("parent") or {}).get("adcode","")).strip()
        rows.append(item)
    return rows

def main():
    os.makedirs(OUT, exist_ok=True)
    print("[1/3] 省级...")
    prov = slim(fetch("100000_full"), "province")
    bc = {p["code"]: p for p in prov}
    prov = [bc[str(c)] for c in PROVINCES if str(c) in bc]
    print("  ", len(prov), "条")

    print("[2/3] 地市（34 次请求）...")
    cities = []
    for i, pc in enumerate(PROVINCES, 1):
        rows = slim(fetch(str(pc)+"_full"), "city")
        name = next((p["name"] for p in prov if p["code"]==str(pc)), str(pc))
        if pc in MUNI:
            for r in rows: r["parent"] = str(pc)
        cities.extend(rows)
        print("  [{:2d}/34] {} {}".format(i, name, len(rows)))
        time.sleep(0.08)

    def dump(fn, rows):
        obj = {"generated": time.strftime("%Y-%m-%d"),
               "source":"aliyun DataV areas_v3 (GCJ-02) -> WGS-84",
               "count": len(rows), "units": rows}
        p = os.path.join(OUT, fn)
        with open(p,"w",encoding="utf-8") as f:
            json.dump(obj,f,ensure_ascii=False,separators=(",",":"))
        print("  {}: {} 条 {} B".format(fn, len(rows), os.path.getsize(p)))

    print("[3/3] 写出...")
    dump("provinces.json", prov)
    dump("cities.json", cities)
    dump("districts.json", [])
    print("\n完成。宜春市坐标自检：")
    for c in cities:
        if c["name"].startswith("宜春"):
            print("  ", c["name"], c["center"], "(实测 WGS-84 约 114.4167,27.8000)")

if __name__ == "__main__":
    main()
