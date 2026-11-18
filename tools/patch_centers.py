# -*- coding: utf-8 -*-
"""给边界数据补 parent 和中心点，让它能独立当"区县单元表"用。

为什么需要：区县的点表 districts.json 是空的（快速版没拉），
而 districtByPolygon 命中后要去点表里查 Unit（要名字、中心点、parent）。
与其再发 500 次请求拉点表，不如直接从已有的边界几何里算出中心点和 parent
（parent 从 adcode 前缀推：3609xx 的 parent 是 360900）。
"""
import json, os

ASSETS = os.path.abspath(os.path.join(os.path.dirname(__file__), "..",
                                      "新工程", "app", "src", "main", "assets", "admin"))


def bbox_center(polys):
    """用外接框中心当代表点。
    不用质心是因为质心算法对凹多边形会跑到外面，外接框中心虽然也不完美，
    但对"气泡画在哪"这个用途足够了，而且计算极快、绝不会是 NaN。
    """
    xs, ys = [], []
    for ring in polys:
        for pt in ring:
            xs.append(pt[0]); ys.append(pt[1])
    if not xs: return [0.0, 0.0]
    return [round((min(xs)+max(xs))/2, 5), round((min(ys)+max(ys))/2, 5)]


def parent_of(code):
    """从 adcode 推上级：3609xx -> 360900；3601xx -> 360100；直辖市区县 1101xx -> 110100(但实际挂 110000)"""
    if len(code) == 6 and code[4:] != "00":
        return code[:4] + "00"
    return ""


def patch(fn):
    path = os.path.join(ASSETS, fn)
    d = json.load(open(path, encoding="utf-8"))
    n_center = 0
    for u in d["units"]:
        if "center" not in u:
            u["center"] = bbox_center(u["polys"]); n_center += 1
        if "parent" not in u:
            u["parent"] = parent_of(u["code"])
    with open(path, "w", encoding="utf-8") as f:
        json.dump(d, f, ensure_ascii=False, separators=(",", ":"))
    print("{}: 补了 {} 个中心点, {} KB".format(fn, n_center, os.path.getsize(path)//1024))


for f in ["province_bounds.json", "city_bounds.json", "district_bounds.json"]:
    patch(f)

# 自检
d = json.load(open(os.path.join(ASSETS, "district_bounds.json"), encoding="utf-8"))
for u in d["units"]:
    if u["code"] == "360983":
        print("高安市:", u["name"], "center=", u["center"], "parent=", u["parent"])
