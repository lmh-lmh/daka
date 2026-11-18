# -*- coding: utf-8 -*-
"""测试新工程要用的 Maven 依赖在当前网络的镜像上是否可达。"""
import urllib.request

TESTS = {
    'coil3 aar (aliyun public)': 'https://maven.aliyun.com/repository/public/io/coil-kt/coil3/coil-android/3.0.4/coil-android-3.0.4.aar',
    'coil2 aar (aliyun public)': 'https://maven.aliyun.com/repository/public/io/coil-kt/coil/2.7.0/coil-2.7.0.aar',
    'room-runtime (aliyun google)': 'https://maven.aliyun.com/repository/google/androidx/room/room-runtime/2.6.1/room-runtime-2.6.1.aar',
    'maplibre 11.13.5 (aliyun public)': 'https://maven.aliyun.com/repository/public/org/maplibre/gl/android-sdk/11.13.5/android-sdk-11.13.5.aar',
    'maplibre 11.13.5 (repo1)': 'https://repo1.maven.org/maven2/org/maplibre/gl/android-sdk/11.13.5/android-sdk-11.13.5.aar',
    'activity-ktx 1.9.3': 'https://maven.aliyun.com/repository/google/androidx/activity/activity-ktx/1.9.3/activity-ktx-1.9.3.aar',
    'exifinterface 1.3.7': 'https://maven.aliyun.com/repository/google/androidx/exifinterface/exifinterface/1.3.7/exifinterface-1.3.7.aar',
    'documentfile 1.0.1': 'https://maven.aliyun.com/repository/google/androidx/documentfile/documentfile/1.0.1/documentfile-1.0.1.aar',
    'lifecycle-viewmodel-ktx 2.8.7': 'https://maven.aliyun.com/repository/google/androidx/lifecycle/lifecycle-viewmodel-ktx/2.8.7/lifecycle-viewmodel-ktx-2.8.7.aar',
}


def head(url):
    try:
        req = urllib.request.Request(url, method='HEAD')
        r = urllib.request.urlopen(req, timeout=15)
        return '{} {}B'.format(r.status, r.headers.get('Content-Length'))
    except Exception as e:
        return 'ERR {}'.format(type(e).__name__)


for k, u in TESTS.items():
    print('{:<38} {}'.format(k, head(u)))
