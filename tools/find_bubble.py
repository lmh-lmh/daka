# -*- coding: utf-8 -*-
"""从截图里找到绿色气泡的位置。

为什么要这个：真机上气泡的位置每步都在变，我一直手算/眼看坐标，
点偏了十几次（点了没反应，还误判成"功能坏了"）。这个脚本用颜色扫描
直接给出精确坐标，比自己估靠谱得多。

气泡底色的四档（见 RegionLayer.renderBubble）：
  count>=50  0xE8590C 橙
  count>=10  0x2E7D32 深绿
  count>=3   0x43A047 绿
  else       0x558B2F 浅绿
"""
import sys
from PIL import Image


def find_bubbles(path, tol=30):
    im = Image.open(path).convert("RGB")
    W, H = im.size
    px = im.load()
    # 实测：气泡在截图上的实际颜色是 (63,150,67) 左右，不是源码里的 (67,160,71)——
    # 因为渲染时有抗锯齿和透明度混合。所以判据改成"绿色占主导且不太暗"，
    # 不做精确色匹配（那样永远对不上）。
    targets = None
    hits = []
    step = 2
    for y in range(0, H, step):
        for x in range(0, W, step):
            r, g, b = px[x, y]
            # 绿底气泡：G 明显高于 R 和 B，且亮度足够（排除深色植被）
            if g > 110 and g - r > 60 and g - b > 60:
                hits.append((x, y))

    if not hits:
        print("没找到气泡（可能这一层是空的，或颜色被底图干扰）")
        return []

    # 简单的连通聚类：按距离把点分组，每组的中心就是一个气泡
    clusters = []
    for (x, y) in hits:
        placed = False
        for c in clusters:
            if abs(c["cx"] - x) < 120 and abs(c["cy"] - y) < 60:
                c["pts"].append((x, y))
                c["cx"] = sum(p[0] for p in c["pts"]) // len(c["pts"])
                c["cy"] = sum(p[1] for p in c["pts"]) // len(c["pts"])
                placed = True
                break
        if not placed:
            clusters.append({"cx": x, "cy": y, "pts": [(x, y)]})

    # 太小的团是噪声（底图上零星绿色像素）
    real = [c for c in clusters if len(c["pts"]) >= 150]
    real.sort(key=lambda c: -len(c["pts"]))
    print("找到 {} 个气泡：".format(len(real)))
    for c in real:
        print("  tap {} {}   (像素数 {})".format(c["cx"], c["cy"], len(c["pts"])))
    return real


if __name__ == "__main__":
    p = sys.argv[1] if len(sys.argv) > 1 else "shot.png"
    find_bubbles(p)
