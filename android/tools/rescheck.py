#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Android 资源引用完整性检查（无 SDK 环境下替代 aapt/lint）。

扫描 src 下所有 .kt 与 .xml，提取对 R.xxx / @xxx/ 的引用，
与 values/drawable/layout 里实际定义的资源对比，报告缺失引用。

同时校验：
  - 所有 XML 是否格式合法
  - res 各目录下的文件名是否为合法资源名（小写+下划线）
"""
import os, re, sys
import xml.etree.ElementTree as ET

RES_TYPES = ('string', 'color', 'dimen', 'style', 'drawable', 'id', 'layout',
             'bool', 'integer', 'array', 'attr', 'fraction')

def main(src_root):
    p = lambda *a: os.path.join(src_root, *a)

    defined = {t: set() for t in RES_TYPES}
    xml_errors, name_errors = [], []

    # ---------- 1. 解析 XML 并收集定义 ----------
    for dirpath, _, files in os.walk(src_root):
        norm = dirpath.replace('\\', '/')
        for f in files:
            path = os.path.join(dirpath, f)
            rel = os.path.relpath(path, src_root)

            if f.endswith('.xml'):
                try:
                    root = ET.parse(path).getroot()
                except ET.ParseError as e:
                    xml_errors.append('%s: %s' % (rel, e))
                    continue

                if '/values' in norm or norm.endswith('values'):
                    for child in root:
                        tag, name = child.tag, child.get('name')
                        if not name:
                            continue
                        if tag == 'style':
                            defined['style'].add(name)
                            parts = name.split('.')
                            for i in range(1, len(parts)):
                                defined['style'].add('.'.join(parts[:i]))
                        elif tag in defined:
                            defined[tag].add(name)
                        elif tag == 'item':
                            t = child.get('type')
                            if t in defined:
                                defined[t].add(name)

                elif '/drawable' in norm:
                    defined['drawable'].add(f[:-4])
                elif '/layout' in norm:
                    defined['layout'].add(f[:-4])
                elif '/mipmap' in norm:
                    defined.setdefault('mipmap', set()).add(f[:-4])

                # 收集 @+id 定义
                try:
                    text = open(path, encoding='utf-8').read()
                except Exception:
                    continue
                for m in re.finditer(r'@\+id/(\w+)', text):
                    defined['id'].add(m.group(1))

    # 资源文件名合法性
    for dirpath, _, files in os.walk(src_root):
        norm = dirpath.replace('\\', '/')
        if not any('/%s' % d in norm for d in ('drawable', 'layout', 'mipmap')):
            continue
        for f in files:
            stem = f.rsplit('.', 1)[0]
            if not re.fullmatch(r'[a-z0-9_]+', stem):
                name_errors.append('%s/%s' % (norm, f))

    # ---------- 2. 检查引用 ----------
    missing = []

    def check_ref(res_type, name, where):
        if res_type in ('mipmap',) and res_type not in defined:
            return
        pool = defined.get(res_type, set())
        if name not in pool:
            missing.append('%s -> @%s/%s' % (where, res_type, name))

    for dirpath, _, files in os.walk(src_root):
        for f in files:
            path = os.path.join(dirpath, f)
            rel = os.path.relpath(path, src_root)
            try:
                text = open(path, encoding='utf-8').read()
            except Exception:
                continue

            if f.endswith('.kt'):
                for m in re.finditer(r'\bR\.(\w+)\.(\w+)', text):
                    check_ref(m.group(1), m.group(2), rel)
            elif f.endswith('.xml'):
                # @xxx/yyy 但排除 @+id 与 @android:
                for m in re.finditer(r'(?<!@\+)@(?!android:)(\w+)/(\w+)', text):
                    check_ref(m.group(1), m.group(2), rel)

    # ---------- 3. 报告 ----------
    print('资源定义统计：')
    for t in sorted(defined):
        if defined[t]:
            print('  %-10s %d' % (t, len(defined[t])))

    ok = True
    if xml_errors:
        ok = False
        print('\nXML 格式错误 (%d)：' % len(xml_errors))
        for e in xml_errors:
            print('  ' + e)
    if name_errors:
        ok = False
        print('\n非法资源文件名 (%d)：' % len(name_errors))
        for e in name_errors:
            print('  ' + e)
    if missing:
        ok = False
        print('\n缺失引用 (%d)：' % len(missing))
        for e in sorted(set(missing)):
            print('  ' + e)

    print('\n%s' % ('全部通过' if ok else '存在问题，见上'))
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else 'app/src'))
