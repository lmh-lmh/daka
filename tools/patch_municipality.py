# -*- coding: utf-8 -*-
"""把直辖市的区县补进 district_bounds.json。

问题根源：DataV 里 110000_full（北京）返回的**直接就是区县**，不是"地市"。
第一次构建时把它们当成了地市写进 city_bounds，于是 district_bounds 里没有北京的区县。

修法：把 4 个直辖市的省级边界按区县再处理一遍，补进 district_bounds。
"""
import json, math, os, time, urllib.request

BASE = "https://geo.datav.aliyun.com/areas_v3/bound/{}.json"
ASSETS = os.path.abspath(os.path.join(os.path.dirname(__file__), "..",
                                      "新工程", "app", "src", "main", "assets", "admin"))
MUNI = [110000, 120000, 310000, 500000]
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
    if len(ring) <= 3: return ring
    keep=[False]*len(ring); keep[0]=keep[-1]=True
    stack=[(0,len(ring)-1)]
    while stack:
        i,j = stack.pop()
        if j<=i+1: continue
        x1,y1=ring[i]; x2,y2=ring[j]
        dx,dy=x2-x1,y2-y1; norm=math.hypot(dx,dy); bi,bd=-1,-1.0
        for k in range(i+1,j):
            x,y=ring[k]
            d=math.hypot(x-x1,y-y1) if norm<1e-12 else abs(dy*x-dx*y+x2*y1-y2*x1)/norm
            if d>bd: bd,bi=d,k
        if bd>tol and bi>0:
            keep[bi]=True; stack.append((i,bi)); stack.append((bi,j))
    return [ring[i] for i in range(len(ring)) if keep[i]]


def extract_rings(geom):
    t=geom.get("type"); c=geom.get("coordinates"); out=[]
    if t=="Polygon" and c: out.append(c[0])
    elif t=="MultiPolygon":
        for p in c or []:
            if p: out.append(p[0])
    return out


def build(features):
    units=[]
    for f in features:
        p=f.get("properties",{})
        code=str(p.get("adcode","")).strip(); name=(p.get("name") or "").strip()
        if not code or not name or "_" in code: continue
        rings=extract_rings(f.get("geometry") or {})
        if not rings: continue
        polys=[]
        for ring in rings:
            conv=[]
            for pt in ring:
                if not isinstance(pt,(list,tuple)) or len(pt)<2: continue
                wl,wa=gcj2wgs(float(pt[0]),float(pt[1]))
                conv.append([round(wl,5),round(wa,5)])
            if len(conv)<3: continue
            t=DIST_TOL; s=simplify(conv,t)
            while len(s)>DIST_MAX_POINTS and t<2.0:
                t*=2; s=simplify(conv,t)
            if len(s)>=3: polys.append(s)
        if polys: units.append({"code":code,"name":name,"polys":polys})
    return units


def main():
    path=os.path.join(ASSETS,"district_bounds.json")
    d=json.load(open(path,encoding="utf-8"))
    have={u["code"] for u in d["units"]}
    added=0
    for pc in MUNI:
        for attempt in range(3):
            try:
                req=urllib.request.Request(BASE.format(str(pc)+"_full"),
                                           headers={"User-Agent":"DakaBuild/1.0"})
                sub=json.loads(urllib.request.urlopen(req,timeout=30).read()); break
            except Exception:
                if attempt==2: sub=None
                time.sleep(1.0)
        if not sub: 
            print("  失败:", pc); continue
        units=build(sub.get("features",[]))
        n=0
        for u in units:
            if u["code"] not in have:
                d["units"].append(u); have.add(u["code"]); added+=1; n+=1
        print("  直辖市 {}: +{} 个区县".format(pc, n))
    d["count"]=len(d["units"])
    with open(path,"w",encoding="utf-8") as fp:
        json.dump(d,fp,ensure_ascii=False,separators=(",",":"))
    print("总计 {} 条, {} KB".format(len(d["units"]), os.path.getsize(path)//1024))
    # 校验北京
    for c,nm in [("110101","东城区"),("110105","朝阳区"),("310101","黄浦区")]:
        print("  ", nm, "有" if c in have else "缺")


if __name__ == "__main__":
    main()
