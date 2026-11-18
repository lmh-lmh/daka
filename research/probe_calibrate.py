# -*- coding: utf-8 -*-
"""校准判据：先算已知占位图（esri_z20.jpg，图上写着 Map data not yet available）的灰度统计，
再用这个基准去判各地瓦片。之前用 std>18 一刀切，把"很暗的真实影像"误判成"无影像"。
"""
import io
from PIL import Image, ImageStat

for f in ['esri_z18.jpg', 'esri_z19.jpg', 'esri_z20.jpg']:
    im = Image.open(f).convert('L')
    st = ImageStat.Stat(im)
    hist = im.histogram()
    # 找最常见灰度值（占位图应该高度集中）
    peak = max(range(256), key=lambda i: hist[i])
    peak_ratio = hist[peak] / float(sum(hist))
    print('{:<16} std={:5.1f} mean={:5.1f} 众数={:3d} 众数占比={:.1%}'.format(
        f, st.stddev[0], st.mean[0], peak, peak_ratio))

print()
print('--- 用"众数占比"再查各地瓦片 ---')
import glob
for f in sorted(glob.glob('probe_*.jpg')):
    im = Image.open(f).convert('L')
    st = ImageStat.Stat(im)
    hist = im.histogram()
    peak = max(range(256), key=lambda i: hist[i])
    peak_ratio = hist[peak] / float(sum(hist))
    print('{:<40} std={:5.1f} mean={:5.1f} 众数占比={:.1%}'.format(
        f, st.stddev[0], st.mean[0], peak_ratio))
