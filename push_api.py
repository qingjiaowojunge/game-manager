#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
在当前网络下用 GitHub Git Data API 代替 `git push`。

为什么需要它：本机到 github.com:443 的连接被出口代理拦截（CONNECT 返回 502），
但 api.github.com 正常，所以 git push 必然失败。这里用 API 走同样的流程：
blob → tree → commit → 更新 ref，等价于一次 push，同样会触发 Actions。

关键设计：**按内容比对**，不看提交谱系。
远端 tree 与本地 `git ls-tree -r HEAD` 逐个 blob sha 对比，只上传差异文件。
因此就算本地 commit sha 和远端对不上（API 会重写 committer/时间），也不会误判。

用法：
    python push_api.py                  # 把本地 HEAD 的内容同步到远端
    python push_api.py --dry            # 只看会改哪些文件
    python push_api.py -m "提交说明"
    python push_api.py --repo owner/name --branch main

前置：凭证在 ~/.git-credentials（https://user:token@github.com）
"""
import argparse
import base64
import io
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request

DEFAULT_REPO = "qingjiaowojunge/game-manager"
DEFAULT_BRANCH = "main"


def die(msg, code=1):
    print("✗ " + msg)
    sys.exit(code)


def load_token():
    path = os.path.expanduser("~/.git-credentials")
    if not os.path.exists(path):
        die("找不到 %s" % path)
    for line in io.open(path, encoding="utf-8").read().splitlines():
        m = re.match(r"https://([^:]+):([^@]+)@github\.com", line.strip())
        if m:
            return m.group(1), m.group(2)
    die("~/.git-credentials 里没有 github.com 的凭证")


def make_opener():
    proxy = os.environ.get("https_proxy") or os.environ.get("HTTPS_PROXY")
    handlers = [urllib.request.ProxyHandler({"http": proxy, "https": proxy})] if proxy else []
    return urllib.request.build_opener(*handlers)


class Api(object):
    def __init__(self, repo, token, opener):
        self.repo = repo
        self.token = token
        self.opener = opener

    def call(self, method, path, body=None):
        data = json.dumps(body).encode("utf-8") if body is not None else None
        req = urllib.request.Request(
            "https://api.github.com" + path,
            data=data,
            method=method,
            headers={
                "Authorization": "Bearer " + self.token,
                "Accept": "application/vnd.github+json",
                "Content-Type": "application/json",
                "User-Agent": "push-api",
            },
        )
        with self.opener.open(req, timeout=60) as r:
            raw = r.read()
        return json.loads(raw) if raw else {}


def git(*args, binary=False):
    p = subprocess.run(["git"] + list(args), capture_output=True)
    if p.returncode != 0:
        die("git %s 失败: %s" % (" ".join(args), p.stderr.decode("utf-8", "ignore").strip()))
    return p.stdout if binary else p.stdout.decode("utf-8", "ignore")


def local_tree():
    """path -> (mode, blob_sha)"""
    out = {}
    for line in git("-c", "core.quotepath=false", "ls-tree", "-r", "HEAD").splitlines():
        if not line.strip():
            continue
        meta, path = line.split("\t", 1)
        mode, typ, sha = meta.split()
        if typ != "blob":
            continue
        out[path] = (mode, sha)
    return out


def remote_tree(api, tree_sha):
    """path -> (mode, blob_sha)，递归展开"""
    out = {}
    stack = [(tree_sha, "")]
    while stack:
        sha, prefix = stack.pop()
        data = api.call("GET", "/repos/%s/git/trees/%s" % (api.repo, sha))
        for e in data.get("tree", []):
            path = prefix + e["path"]
            if e["type"] == "tree":
                stack.append((e["sha"], path + "/"))
            elif e["type"] == "blob":
                out[path] = (e.get("mode") or "100644", e["sha"])
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", default=DEFAULT_REPO)
    ap.add_argument("--branch", default=DEFAULT_BRANCH)
    ap.add_argument("-m", "--message", default=None)
    ap.add_argument("--dry", action="store_true")
    args = ap.parse_args()

    if subprocess.run(["git", "rev-parse", "--show-toplevel"], capture_output=True).returncode != 0:
        die("当前目录不在 git 仓库里")

    user, token = load_token()
    api = Api(args.repo, token, make_opener())
    print("认证账号: %s" % api.call("GET", "/user")["login"])

    ref = api.call("GET", "/repos/%s/git/ref/heads/%s" % (args.repo, args.branch))
    remote_sha = ref["object"]["sha"]
    remote_commit = api.call("GET", "/repos/%s/git/commits/%s" % (args.repo, remote_sha))
    remote_base_tree = remote_commit["tree"]["sha"]
    print("远端 %s: %s (tree %s)" % (args.branch, remote_sha[:10], remote_base_tree[:10]))

    loc = local_tree()
    rem = remote_tree(api, remote_base_tree)
    print("本地文件 %d 个 / 远端文件 %d 个" % (len(loc), len(rem)))

    entries = []   # 送进新 tree 的条目
    added, modified, deleted = [], [], []
    for path, (mode, sha) in sorted(loc.items()):
        if path not in rem:
            added.append(path)
        elif rem[path][1] != sha:
            modified.append(path)
        else:
            continue
        entries.append({"path": path, "mode": mode, "type": "blob", "sha": sha})
    for path in sorted(rem):
        if path not in loc:
            deleted.append(path)
            entries.append({"path": path, "mode": "100644", "type": "blob", "sha": None})

    if not entries:
        print("✓ 内容已与远端一致，无需推送")
        return

    print("新增 %d / 修改 %d / 删除 %d" % (len(added), len(modified), len(deleted)))
    for p in added:
        print("   A " + p)
    for p in modified:
        print("   M " + p)
    for p in deleted:
        print("   D " + p)
    if args.dry:
        print("（--dry，未写远端）")
        return

    # 远端没有的 blob 才上传
    for e in entries:
        if e["sha"] is None:
            continue
        try:
            api.call("GET", "/repos/%s/git/blobs/%s" % (args.repo, e["sha"]))
        except urllib.error.HTTPError as ex:
            if ex.code != 404:
                raise
            raw = git("cat-file", "blob", e["sha"], binary=True)
            up = api.call("POST", "/repos/%s/git/blobs" % args.repo, {
                "content": base64.b64encode(raw).decode("ascii"),
                "encoding": "base64",
            })
            print("   upload %s -> %s (%d bytes)" % (e["sha"][:10], up["sha"][:10], len(raw)))
            e["sha"] = up["sha"]

    msg = args.message or git("log", "-1", "--pretty=%B").strip()
    author = {
        "name": git("log", "-1", "--pretty=%an").strip(),
        "email": git("log", "-1", "--pretty=%ae").strip(),
        "date": git("log", "-1", "--pretty=%aI").strip(),
    }

    tree = api.call("POST", "/repos/%s/git/trees" % args.repo,
                    {"base_tree": remote_base_tree, "tree": entries})
    print("新 tree: %s" % tree["sha"][:10])

    commit = api.call("POST", "/repos/%s/git/commits" % args.repo, {
        "message": msg, "tree": tree["sha"], "parents": [remote_sha], "author": author,
    })
    print("新 commit: %s" % commit["sha"])

    api.call("PATCH", "/repos/%s/git/refs/heads/%s" % (args.repo, args.branch),
             {"sha": commit["sha"], "force": False})
    print("✓ 已推送 %s/%s -> %s" % (args.repo, args.branch, commit["sha"]))
    print("  内容校验: 远端 tree == 本地 HEAD tree ?",
          tree["sha"] == git("rev-parse", "HEAD^{tree}").strip())


if __name__ == "__main__":
    main()
