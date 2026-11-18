# -*- coding: utf-8 -*-
"""构建行政区划 assets：拉 DataV 数据 → GCJ-02 转 WGS-84 → 精简成"只有中心点"的 JSON。

为什么要这一步（实测依据，见 06-分层足迹地图-架构与计划.md 第二节）：
1. DataV 只有多边形边界，582 KB（省级）+ 每省上百 KB，全塞进 APK 太重，运行时也用不到几何
2. DataV 的 center 是 GCJ-02 火星坐标，宜春市给 114.391 而实际 WGS-84 是 114.417，
   差约 2.6 km —— 不转的话地图上图标会整体偏移
3. 省级数据里有一条 adcode=100000_JD 的九段线，没有 center / level / name，必须过滤
4. 河北、甘肃缺 centroid，用 center 兜底

产物（写入 app/src/main/assets/admin/）：
    provinces.json   34 条省级
    cities.json      全国地级市（带 parent_code）
    districts.json   全国区县（带 parent_code）
"""
import json
import math
import os
import time
import urllib.request

BASE = "https://geo.datav.aliyun.com/areas_v3/bound/{}.json"
OUT_DIR = os.path.join(os.path.dirname(__file__), "..", "新工程", "app", "src", "main", "assets", "admin")
OUT_DIR = os.path.abspath(OUT_DIR)

# 全国省级 adcode（不含九段线 100000_JD）
PROVINCE_CODES = [
    110000, 120000, 130000, 140000, 150000, 210000, 220000, 230000,
    310000, 320000, 330000, 340000, 350000, 360000, 370000, 410000,
    420000, 430000, 440000, 450000, 460000, 500000, 510000, 520000,
    530000, 540000, 610000, 620000, 630000, 640000, 650000, 710000,
    810000, 820000,
]

# 直辖市：省级本身就是地市级，下面直接是区县
MUNICIPALITIES = {110000, 120000, 310000, 500000}
# 港澳台：childrenNum 常为 0 或结构特殊，单独兜底
SPECIAL = {710000, 810000, 820000}


def wgs2gcj_offset_is_needed():
    return True


def gcj02_to_wgs84(lon, lat):
    """GCJ-02 → WGS-84（迭代反解，精度优于 1 米）。

    为什么不用一次性近似公式：那是"近似"反解，误差可达 1~2 米；打点归类够用，
    但这里成本也就是 30 次迭代的浮点运算，直接做主流的迭代版更稳。
    """
    a = 6378245.0
    ee = 0.00669342162296594323

    def out_of_china(lng, lat_):
        return not (73.66 < lng < 135.05 and 3.86 < lat_ < 53.55)

    def transform_lat(x, y):
        ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * math.sqrt(abs(x))
        ret += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
        ret += (20.0 * math.sin(y * math.pi) + 40.0 * math.sin(y / 3.0 * math.pi)) * 2.0 / 3.0
        ret += (160.0 * math.sin(y / 12.0 * math.pi) + 320 * math.sin(y * math.pi / 30.0)) * 2.0 / 3.0
        return ret

    def transform_lon(x, y):
        ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * math.sqrt(abs(x))
        ret += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
        ret += (20.0 * math.sin(x * math.pi) + 40.0 * math.sin(x / 3.0 * math.pi)) * 2.0 / 3.0
        ret += (150.0 * math.sin(x / 12.0 * math.pi) + 300.0 * math.sin(x / 30.0 * math.pi)) * 2.0 / 3.0
        return ret

    if out_of_china(lon, lat):
        return lon, lat

    d_lat = transform_lat(lon - 105.0, lat - 35.0)
    d_lon = transform_lon(lon - 105.0, lat - 35.0)
    rad_lat = lat / 180.0 * math.pi
    magic = 1 - ee * math.sin(rad_lat) ** 2
    sqrt_magic = math.sqrt(magic)
    d_lat = (d_lat * 180.0) / ((a * (1 - ee)) / (magic * sqrt_magic) * math.pi)
    d_lon = (d_lon * 180.0) / (a / sqrt_magic * math.cos(rad_lat) * math.pi)
    # 一阶近似值
    mg_lon = lon - d_lon
    mg_lat = lat - d_lat
    # 迭代修正（把近似点正算回 GCJ 再比较）
    for _ in range(30):
        t_lon, t_lat = wgs84_to_gcj02_raw(mg_lon, mg_lat, a, ee, transform_lat, transform_lon)
        dd_lon = t_lon - lon
        dd_lat = t_lat - lat
        mg_lon -= dd_lon
        mg_lat -= dd_lat
        if abs(dd_lon) < 1e-9 and abs(dd_lat) < 1e-9:
            break
    return mg_lon, mg_lat


def wgs84_to_gcj02_raw(lon, lat, a, ee, transform_lat, transform_lon):
    d_lat = transform_lat(lon - 105.0, lat - 35.0)
    d_lon = transform_lon(lon - 105.0, lat - 35.0)
    rad_lat = lat / 180.0 * math.pi
    magic = 1 - ee * math.sin(rad_lat) ** 2
    sqrt_magic = math.sqrt(magic)
    d_lat = (d_lat * 180.0) / ((a * (1 - ee)) / (magic * sqrt_magic) * math.pi)
    d_lon = (d_lon * 180.0) / (a / sqrt_magic * math.cos(rad_lat) * math.pi)
    return lon + d_lon, lat + d_lat


def gcj_point(p):
    if not p or len(p) < 2:
        return None
    lon, lat = float(p[0]), float(p[1])
    w_lon, w_lat = gcj02_to_wgs84(lon, lat)
    return [round(w_lon, 6), round(w_lat, 6)]


def fetch(adcode):
    url = BASE.format(adcode)
    for attempt in range(3):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "DakaBuild/1.0"})
            return json.loads(urllib.request.urlopen(req, timeout=30).read())
        except Exception as e:
            if attempt == 2:
                print("  拉取失败 {}: {}".format(adcode, e))
                return None
            time.sleep(1.5)


def slim_features(data, level_name):
    """把 FeatureCollection 精简成单位列表，过滤脏数据，坐标转 WGS-84。"""
    out = []
    if not data:
        return out
    for f in data.get("features", []):
        p = f.get("properties", {})
        code = str(p.get("adcode", "")).strip()
        name = (p.get("name") or "").strip()
        # 九段线：adcode 形如 100000_JD，无 center、无 level、无 name
        if not code or not name or "_" in code:
            continue
        center = p.get("center")
        if not center:
            continue
        w_center = gcj_point(center)
        if not w_center:
            continue
        # centroid 可能缺失（河北、甘肃），用 center 兜底
        w_centroid = gcj_point(p.get("centroid")) or w_center
        parent = p.get("parent") or {}
        item = {
            "code": code,
            "name": name,
            "center": w_center,
            "centroid": w_centroid,
            "children": int(p.get("childrenNum") or 0),
        }
        if level_name != "province":
            item["parent"] = str(parent.get("adcode", "")).strip()
        out.append(item)
    return out


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    print("输出目录:", OUT_DIR)

    # ---- 省级 ----
    print("\n[1/3] 省级")
    prov_raw = fetch(100000)
    provinces = slim_features(prov_raw, "province")
    # 按 PROVINCE_CODES 白名单顺序排，保证稳定
    by_code = {p["code"]: p for p in provinces}
    provinces = [by_code[str(c)] for c in PROVINCE_CODES if str(c) in by_code]
    missing = [c for c in PROVINCE_CODES if str(c) not in by_code]
    print("  省级 {} 条，缺失 {}".format(len(provinces), missing or "无"))

    # ---- 地市 + 区县 ----
    cities, districts = [], []
    for idx, pc in enumerate(PROVINCE_CODES, 1):
        code = str(pc)
        pname = next((p["name"] for p in provinces if p["code"] == code), code)
        data = fetch(code + "_full")
        items = slim_features(data, "city" if pc not in MUNICIPALITIES else "district")
        if pc in MUNICIPALITIES:
            # 直辖市：这一层就是区县，直接挂到省级下面
            for it in items:
                it["parent"] = code
            districts.extend(items)
            print("  [{:2d}/34] {} 直辖市，区县 {} 条".format(idx, pname, len(items)))
            continue
        cities.extend(items)
        print("  [{:2d}/34] {} 地市 {} 条".format(idx, pname, len(items)))
        # 拉每个地市的区县
        for c in items:
            sub = fetch(c["code"] + "_full")
            subs = slim_features(sub, "district")
            for s in subs:
                s["parent"] = c["code"]
            districts.extend(subs)
            time.sleep(0.05)

    # 港澳台：childrenNum=0 时没有下级，跳过
    print("\n汇总: 省 {} / 市 {} / 区县 {}".format(len(provinces), len(cities), len(districts)))

    def dump(name, rows):
        obj = {
            "generated": time.strftime("%Y-%m-%d"),
            "source": "aliyun DataV areas_v3 (GCJ-02) -> WGS-84",
            "count": len(rows),
            "units": rows,
        }
        path = os.path.join(OUT_DIR, name)
        with open(path, "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=False, separators=(",", ":"))
        size = os.path.getsize(path)
        print("  {} -> {} 条 {} B".format(name, len(rows), size))

    print("\n[2/3] 写出")
    dump("provinces.json", provinces)
    dump("cities.json", cities)
    dump("districts.json", districts)

    # ---- 自检 ----
    print("\n[3/3] 自检")
    yc = [c for c in cities if c["name"].startswith("宜春")]
    if yc:
        print("  宜春市(转为WGS-84):", yc[0]["center"], "code=", yc[0]["code"])
        print("  对照实测 WGS-84 约 114.4167, 27.8000")
    for c in provinces[:3]:
        print("  省样例:", c["name"], c["center"])


if __name__ == "__main__":
    main()
