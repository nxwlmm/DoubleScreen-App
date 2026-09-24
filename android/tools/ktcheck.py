#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Kotlin 源码轻量词法检查：
  1) 括号 / 方括号 / 圆括号 是否配平（忽略注释与字符串内部）
  2) 字符串字面量、raw string、字符字面量、块注释是否闭合
  3) 字符串插值 ${...} 是否正确闭合
  4) raw string 内是否混入了未转义的 $（Kotlin 会当作模板前缀）
不替代编译器，只用来在无工具链环境下抓低级错误。
"""
import sys, io, os

def check(path):
    src = io.open(path, encoding='utf-8').read()
    i, n, line = 0, len(src), 1
    stack, errors, warns = [], [], []
    state = 'normal'
    depth = 0
    raw_dollars = []

    while i < n:
        c = src[i]
        if c == '\n':
            line += 1

        if state == 'normal':
            if src.startswith('//', i):
                state = 'line_comment'; i += 2; continue
            if src.startswith('/*', i):
                state = 'block_comment'; depth = 1; i += 2; continue
            if src.startswith('"""', i):
                state = 'raw'; raw_start_line = line; i += 3; continue
            if c == '"':
                state = 'string'; i += 1; continue
            if c == "'":
                state = 'char'; i += 1; continue
            if c == '`':
                # Kotlin 反引号标识符（如测试方法名），内部内容不参与括号统计
                j = i + 1
                while j < n and src[j] != '`' and src[j] != '\n':
                    j += 1
                if j >= n or src[j] != '`':
                    errors.append('L%d: 反引号标识符未闭合' % line)
                i = j + 1; continue
            if c in '({[':
                stack.append((c, line))
            elif c in ')}]':
                if not stack:
                    errors.append('L%d: 多余的 %s' % (line, c))
                else:
                    o, ol = stack.pop()
                    if '({['.index(o) != ')}]'.index(c):
                        errors.append('L%d: %s 与 L%d 的 %s 不匹配' % (line, c, ol, o))
            i += 1; continue

        if state == 'line_comment':
            if c == '\n':
                state = 'normal'
            i += 1; continue

        if state == 'block_comment':
            if src.startswith('/*', i):
                depth += 1; i += 2; continue
            if src.startswith('*/', i):
                depth -= 1; i += 2
                if depth == 0:
                    state = 'normal'
                continue
            i += 1; continue

        if state == 'raw':
            if src.startswith('"""', i):
                state = 'normal'; i += 3; continue
            if c == '$' and not (i + 1 < n and src[i + 1] == '{'):
                raw_dollars.append(line)
            i += 1; continue

        if state == 'string':
            if c == '\\':
                i += 2; continue
            if c == '"':
                state = 'normal'; i += 1; continue
            if c == '$' and i + 1 < n and src[i + 1] == '{':
                j, d2, start_line = i + 2, 1, line
                while j < n and d2 > 0:
                    ch = src[j]
                    if ch == '\n':
                        line += 1
                    elif ch == '{':
                        d2 += 1
                    elif ch == '}':
                        d2 -= 1
                    j += 1
                if d2 != 0:
                    errors.append('L%d: 字符串插值 ${ 未闭合' % start_line)
                i = j; continue
            i += 1; continue

        if state == 'char':
            if c == '\\':
                i += 2; continue
            if c == "'":
                state = 'normal'; i += 1; continue
            i += 1; continue

    if state != 'normal':
        errors.append('文件结束时仍处于 [%s] 状态（未闭合）' % state)
    if stack:
        errors.append('未闭合括号: ' + ', '.join('%s@L%d' % (o, l) for o, l in stack))
    if raw_dollars:
        warns.append('raw string 内出现 $ 于行 ' + ','.join(map(str, raw_dollars[:8])))

    return errors, warns


def main(root):
    total_files = total_err = 0
    for dirpath, _, files in os.walk(root):
        for f in sorted(files):
            if not f.endswith('.kt'):
                continue
            total_files += 1
            p = os.path.join(dirpath, f)
            errs, warns = check(p)
            rel = os.path.relpath(p, root)
            if errs:
                total_err += len(errs)
                print('[FAIL] %s' % rel)
                for e in errs:
                    print('        %s' % e)
            else:
                print('[ OK ] %s' % rel)
            for w in warns:
                print('        [warn] %s' % w)
    print('\n共 %d 个 .kt 文件，%d 个问题' % (total_files, total_err))
    return 1 if total_err else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else '.'))
