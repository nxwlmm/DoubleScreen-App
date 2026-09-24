#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 JsonScanner.kt 的算法**逐行翻译**成 Python，用真实测试数据验证逻辑正确性。

本机没有 Kotlin 编译器，所以用这种方式在提交前抓出算法错误 ——
上一轮就是因为"只在真实运行时才暴露"，白白多跑了一轮 CI。
"""
import json as pyjson

class Malformed(Exception):
    pass

class JsonScanner:
    def __init__(self, src):
        self.src = src
        self.pos = 0

    # ---------- 与 Kotlin 版逐行对应 ----------
    def peek(self):
        return self.src[self.pos] if self.pos < len(self.src) else None

    def skip_ws(self):
        while self.pos < len(self.src) and self.src[self.pos] in ' \t\n\r':
            self.pos += 1

    def expect(self, c):
        if self.pos >= len(self.src) or self.src[self.pos] != c:
            raise Malformed("期望 '%s' @%d" % (c, self.pos))
        self.pos += 1

    def expect_literal(self, lit):
        if not self.src.startswith(lit, self.pos):
            raise Malformed("期望 %s @%d" % (lit, self.pos))
        self.pos += len(lit)

    def read_raw_string(self):
        self.expect('"')
        start = self.pos
        i = self.pos
        need_slow = False
        while i < len(self.src):
            if self.src[i] == '\\':
                need_slow = True
                break
            if self.src[i] == '"':
                raw = self.src[start:i]
                self.pos = i + 1
                return raw
            i += 1
        if not need_slow:
            raise Malformed("字符串未闭合")

        # 慢路径
        self.pos = start
        out = []
        while True:
            if self.pos >= len(self.src):
                raise Malformed("字符串未闭合")
            c = self.src[self.pos]
            if c == '"':
                self.pos += 1
                return ''.join(out)
            if c == '\\':
                self.pos += 1
                e = self.src[self.pos]
                mp = {'"': '"', '\\': '\\', '/': '/', 'b': '\b', 'f': '\f',
                      'n': '\n', 'r': '\r', 't': '\t'}
                if e == 'u':
                    hexs = self.src[self.pos + 1:self.pos + 5]
                    out.append(chr(int(hexs, 16)))
                    self.pos += 4
                elif e in mp:
                    out.append(mp[e])
                else:
                    raise Malformed("未知转义")
                self.pos += 1
            else:
                out.append(c)
                self.pos += 1

    def skip_bare_token(self):
        start = self.pos
        while self.pos < len(self.src):
            c = self.src[self.pos]
            if c.isalnum() or c in '-+.eE':
                self.pos += 1
            else:
                break
        if self.pos == start:
            raise Malformed("非法值起始字符 @%d" % self.pos)

    def skip_any_container(self):
        depth = 0
        while True:
            self.skip_ws()
            c = self.peek()
            if c is None:
                raise Malformed("容器未闭合")
            if c == '"':
                self.read_raw_string()
            elif c in '{[':
                depth += 1
                self.pos += 1
            elif c in '}]':
                depth -= 1
                self.pos += 1
                if depth == 0:
                    return
            else:
                self.pos += 1

    def skip_value(self):
        self.skip_ws()
        c = self.peek()
        if c in '{[':
            self.skip_any_container()
        elif c == '"':
            self.read_raw_string()
        elif c == 't':
            self.expect_literal("true")
        elif c == 'f':
            self.expect_literal("false")
        elif c == 'n':
            self.expect_literal("null")
        elif c is None:
            raise Malformed("内容意外结束")
        else:
            self.skip_bare_token()

    def count_array_elements(self):
        if self.peek() != '[':
            self.skip_value()
            return 0
        self.pos += 1
        self.skip_ws()
        if self.peek() == ']':
            self.pos += 1
            return 0
        count = 0
        while True:
            self.skip_value()
            count += 1
            self.skip_ws()
            c = self.peek()
            if c == ',':
                self.pos += 1
                self.skip_ws()
                continue
            if c == ']':
                self.pos += 1
                return count
            raise Malformed("数组元素之间期望 , 或 ] @%d" % self.pos)

    def read_string_array(self, limit):
        if self.peek() != '[':
            self.skip_value()
            return []
        self.pos += 1
        self.skip_ws()
        if self.peek() == ']':
            self.pos += 1
            return []
        out = []
        while True:
            self.skip_ws()
            if self.peek() == '"':
                v = self.read_raw_string()
                if len(out) < limit and v.strip():
                    out.append(v.strip())
            else:
                self.skip_value()
            self.skip_ws()
            c = self.peek()
            if c == ',':
                self.pos += 1
                continue
            if c == ']':
                self.pos += 1
                return out
            raise Malformed("数组元素之间期望 , 或 ] @%d" % self.pos)

    def read_child_entry(self):
        self.expect('{')
        self.skip_ws()
        if self.peek() == '}':
            self.pos += 1
            return (None, None)
        url = None
        name = None
        while True:
            self.skip_ws()
            key = self.read_raw_string()
            self.skip_ws()
            self.expect(':')
            self.skip_ws()
            if self.peek() == '"':
                v = self.read_raw_string()
                if key in ('sourceUrl', 'url', 'api') and url is None:
                    url = v.strip()
                elif key in ('sourceName', 'name') and name is None:
                    name = v.strip()
            else:
                self.skip_value()
            self.skip_ws()
            c = self.peek()
            if c == ',':
                self.pos += 1
                continue
            if c == '}':
                self.pos += 1
                break
            raise Malformed("子仓对象成员之间期望 , 或 } @%d" % self.pos)
        return (url if url and url.startswith('http') else None, name)

    def read_store_house(self, limit):
        if self.peek() != '[':
            self.skip_value()
            return ([], None)
        self.pos += 1
        self.skip_ws()
        if self.peek() == ']':
            self.pos += 1
            return ([], None)
        urls = []
        first_name = None
        while True:
            self.skip_ws()
            c = self.peek()
            if c == '"':
                direct = self.read_raw_string().strip()
                if direct.startswith('http') and len(urls) < limit:
                    urls.append(direct)
            elif c == '{':
                u, n = self.read_child_entry()
                if u and len(urls) < limit:
                    urls.append(u)
                if first_name is None:
                    first_name = n
            else:
                self.skip_value()
            self.skip_ws()
            c = self.peek()
            if c == ',':
                self.pos += 1
                continue
            if c == ']':
                self.pos += 1
                return (urls, first_name)
            raise Malformed("storeHouse 数组未闭合 @%d" % self.pos)

    def scan_top_level_object(self, on_member):
        self.skip_ws()
        self.expect('{')
        self.skip_ws()
        if self.peek() == '}':
            self.pos += 1
            return
        while True:
            self.skip_ws()
            name = self.read_raw_string()
            self.skip_ws()
            self.expect(':')
            self.skip_ws()
            before = self.pos
            on_member(name)
            if self.pos == before:
                self.skip_value()
            self.skip_ws()
            c = self.peek()
            if c == ',':
                self.pos += 1
                continue
            if c == '}':
                self.pos += 1
                return
            raise Malformed("对象成员之间期望 , 或 } @%d" % self.pos)


# ============================================================ 复刻 SourceProbe.scanJson
def scan_json(text):
    r = {'siteCount': 0, 'liveCount': 0, 'spider': None, 'childUrls': [], 'declaredName': None}
    s = JsonScanner(text)
    MAX_CHILD_URLS = 64

    def on_member(name):
        if name == 'sites':
            r['siteCount'] = s.count_array_elements()
        elif name == 'lives':
            r['liveCount'] = s.count_array_elements()
        elif name == 'spider':
            r['spider'] = s.read_raw_string() if s.peek() == '"' else (s.skip_value() or None)
        elif name == 'urls':
            r['childUrls'] = s.read_string_array(MAX_CHILD_URLS)
        elif name == 'storeHouse':
            u, n = s.read_store_house(MAX_CHILD_URLS)
            r['childUrls'] = u
            if n is not None:
                r['declaredName'] = n
        elif name in ('name', 'sourceName'):
            v = s.read_raw_string() if s.peek() == '"' else (s.skip_value() or None)
            if r['declaredName'] is None and v is not None:
                r['declaredName'] = v
        else:
            s.skip_value()

    try:
        s.scan_top_level_object(on_member)
    except Malformed as e:
        return {'error': str(e)}

    kind = 'MULTI' if r['childUrls'] else 'SINGLE'
    item = len(r['childUrls']) if kind == 'MULTI' else r['siteCount'] + r['liveCount']
    r['kind'] = kind
    r['itemCount'] = item
    return r


# ============================================================ 测试
SINGLE_OK = '''
    {"sites":[{"key":"a","name":"站点A","type":3,"api":"csp_A"},
              {"key":"b","name":"站点B","type":3,"api":"csp_B"}],
     "spider":"https://cdn.example.com/spider.jar;md5;abc123",
     "lives":[]}
'''
SINGLE_NO_SPIDER = '{"sites":[{"key":"a","name":"站点A"}]}'
SINGLE_EMPTY_SITES = '{"sites":[],"spider":"https://cdn.example.com/spider.jar"}'
MULTI_OK = ('{"storeHouse":[{"sourceName":"仓A","sourceUrl":"https://a.example.com/1.json"},'
            '{"sourceName":"仓B","sourceUrl":"https://b.example.com/2.json"}]}')
MULTI_FLAT = '{"urls":["https://a.com/1.json","https://b.com/2.json"]}'

cases = [
    ("SINGLE_OK", SINGLE_OK, {'siteCount': 2, 'spider': 'https://cdn.example.com/spider.jar;md5;abc123', 'kind': 'SINGLE', 'itemCount': 2}),
    ("SINGLE_NO_SPIDER", SINGLE_NO_SPIDER, {'siteCount': 1, 'spider': None, 'kind': 'SINGLE', 'itemCount': 1}),
    ("SINGLE_EMPTY_SITES", SINGLE_EMPTY_SITES, {'siteCount': 0, 'spider': 'https://cdn.example.com/spider.jar', 'itemCount': 0}),
    ("MULTI_OK", MULTI_OK, {'kind': 'MULTI', 'itemCount': 2, 'declaredName': '仓A',
                            'childUrls': ['https://a.example.com/1.json', 'https://b.example.com/2.json']}),
    ("MULTI_FLAT", MULTI_FLAT, {'kind': 'MULTI', 'itemCount': 2}),
    # 边界：转义、嵌套、unicode、混合括号
    ("escaped", '{"sites":[{"n":"a\\"b"}],"spider":"x"}', {'siteCount': 1, 'spider': 'x'}),
    ("unicode", '{"sites":[{"n":"\\u4e2d\\u6587"}],"spider":"y"}', {'siteCount': 1, 'spider': 'y'}),
    ("nested", '{"a":{"b":[1,2,{"c":"}"}]},"sites":[1],"spider":"z"}', {'siteCount': 1, 'spider': 'z'}),
    ("mixed-brackets", '{"a":[{"b":1},[2],{"c":[3]}],"sites":[1,2,3],"spider":"w"}', {'siteCount': 3, 'spider': 'w'}),
    ("unknown-keys", '{"x":1,"y":"str","z":{"deep":[1,2]},"sites":[{},{}],"spider":"q"}', {'siteCount': 2, 'spider': 'q'}),
    ("storeHouse-flat-str", '{"storeHouse":["https://a.com/1.json","https://b.com/2.json"]}', {'kind': 'MULTI', 'itemCount': 2}),
    ("name-key", '{"name":"我的仓","sites":[1],"spider":"s"}', {'declaredName': '我的仓', 'siteCount': 1}),
]

print("=" * 70)
print("JsonScanner 算法验证（Python 逐行复刻 Kotlin 实现）")
print("=" * 70)
fails = 0
for name, src, expect in cases:
    got = scan_json(src)
    bad = []
    if 'error' in got:
        bad.append('解析异常: ' + got['error'])
    else:
        for k, v in expect.items():
            if got.get(k) != v:
                bad.append('%s: 期望 %r 实际 %r' % (k, v, got.get(k)))
    # 交叉验证：Python 内置 json 也能解析（确认测试数据本身合法）
    try:
        pyjson.loads(src)
        valid = True
    except Exception as e:
        valid = False
        bad.append('测试数据本身不是合法 JSON: %s' % e)
    if bad:
        fails += 1
        print('  [FAIL] %-20s %s' % (name, '; '.join(bad)))
    else:
        print('  [ OK ] %-20s kind=%-7s itemCount=%s spider=%s' % (
            name, got.get('kind'), got.get('itemCount'), (got.get('spider') or '-')[:28]))

print()
print("额外：真实源文件（多仓 storeHouse 带额外字段）")
real = '''{
  "storeHouse": [
    {"sourceName": "饭太硬", "sourceUrl": "https://example.com/1.json", "extra": [1,2,{"x":"}"}]},
    {"sourceName": "肥猫", "sourceUrl": "https://example.com/2.json"}
  ],
  "urls": ["https://fallback.com/a.json"]
}'''
print("   ", scan_json(real))

print()
print("结论:", "全部通过 ✓" if fails == 0 else "%d 项失败 ✗" % fails)
