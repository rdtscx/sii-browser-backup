#!/usr/bin/env python3
"""跟随上游仓库改名 / 迁移 / 默认分支变更。

GitHub 对改名后的仓库保留 301 重定向，但只要旧名被别人占用，重定向就失效。
本脚本每次同步前先解析上游的真实全名，把结果回写到仓库内的配置，做到：
  1. 上游改名后，本地仍然跟着新地址同步，不需要人工改 workflow；
  2. 上游默认分支从 main 变 master 之类，也能自动跟上；
  3. 页面页脚的「上游」链接、仓库简介里的上游地址同步改成新地址。

配置来源：仓库根目录 upstream.json
  {
    "repo": "wanwuwww/Sii-Browser",   // 当前记录的上游全名
    "branch": "auto",                  // auto = 跟随上游默认分支
    "aliases": ["wanwuwww/Sii"],       // 历史名字，旧名被占用时用它们找回
    "mode": "mirror",                  // mirror = 镜像代码与 Release；reference = 只做引用
    "resolved_at": "2026-11-27T...",
    "history": [{"from": "wanwuwww/Sii", "to": "wanwuwww/Sii-Browser", "at": "..."}]
  }

用法（在仓库根目录）：
  GH_TOKEN=... python3 scripts/resolve_upstream.py            # 只解析并打印
  GH_TOKEN=... python3 scripts/resolve_upstream.py --apply    # 解析并回写配置
  GH_TOKEN=... python3 scripts/resolve_upstream.py --json     # 输出 JSON（给脚本消费）

GitHub Actions 会读取以下输出：repo / branch / status / renamed / mode
"""

import argparse
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone

CONFIG_NAME = "upstream.json"


def now_iso():
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def gh_api(path):
    """调用 gh api；失败返回 None。gh 会跟随 GitHub 的改名 301 重定向。"""
    try:
        out = subprocess.check_output(
            ["gh", "api", path], stderr=subprocess.PIPE, text=True
        )
        return json.loads(out)
    except subprocess.CalledProcessError:
        return None


def probe(full_name):
    data = gh_api("repos/%s" % full_name)
    if not data or not data.get("full_name"):
        return None
    return {
        "repo": data["full_name"],
        "branch": data.get("default_branch") or "",
        "archived": bool(data.get("archived")),
        "disabled": bool(data.get("disabled")),
        "pushed_at": data.get("pushed_at") or "",
        "description": data.get("description") or "",
    }


def resolve(cfg):
    """按 当前名 → 历史别名 的顺序探活，返回真实仓库信息。"""
    names = []
    if cfg.get("repo"):
        names.append(cfg["repo"])
    for old in cfg.get("aliases") or []:
        if old not in names:
            names.append(old)

    tried = []
    for name in names:
        info = probe(name)
        tried.append({"name": name, "ok": bool(info), "resolved": (info or {}).get("repo", "")})
        if info:
            info["tried"] = tried
            return info
    return {"tried": tried}


def load_cfg(root):
    path = os.path.join(root, CONFIG_NAME)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as f:
            return json.load(f), path
    # 首次接入：从现有 workflow 的 UPSTREAM_REPO 推断
    wf = os.path.join(root, ".github", "workflows", "sync-upstream.yml")
    repo = ""
    branch = "auto"
    if os.path.exists(wf):
        src = open(wf, encoding="utf-8").read()
        m = re.search(r"UPSTREAM_REPO:\s*([^\s#]+)", src)
        if m:
            repo = m.group(1).strip('"\'')
        m = re.search(r"UPSTREAM_BRANCH:\s*([^\s#]+)", src)
        if m:
            branch = m.group(1).strip('"\'')
    return {
        "repo": repo,
        "branch": branch or "auto",
        "aliases": [],
        "mode": "mirror",
        "resolved_at": "",
        "history": [],
    }, path


def patch_build_downloads(root, repo_name, new_up):
    """把 scripts/build_downloads.py 里对应仓库的 up 字段改成新上游全名。"""
    path = os.path.join(root, "scripts", "build_downloads.py")
    if not os.path.exists(path):
        return False
    src = open(path, encoding="utf-8").read()
    pattern = re.compile(
        r"('%s'\s*:\s*dict\([^)]*?up=')([^']*)(')" % re.escape(repo_name)
    )
    new_src, n = pattern.subn(lambda m: m.group(1) + new_up + m.group(3), src)
    if n and new_src != src:
        open(path, "w", encoding="utf-8").write(new_src)
        return True
    return False


def patch_description(repo_name, old_up, new_up):
    """仓库简介里如果写着旧上游地址，同步改成新地址。"""
    data = gh_api("repos/rdtscx/%s" % repo_name)
    if not data:
        return False
    desc = data.get("description") or ""
    if not desc or old_up not in desc:
        return False
    new_desc = desc.replace(old_up, new_up)
    try:
        subprocess.check_call(
            ["gh", "api", "-X", "PATCH", "repos/rdtscx/%s" % repo_name,
             "-f", "description=%s" % new_desc],
            stdout=subprocess.DEVNULL,
        )
    except subprocess.CalledProcessError:
        return False
    return True


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=".", help="仓库根目录")
    ap.add_argument("--repo-name", default="", help="本仓库名（rdtscx/<repo-name>）")
    ap.add_argument("--apply", action="store_true", help="把解析结果回写到配置与页面脚本")
    ap.add_argument("--json", action="store_true", help="以 JSON 打印结果")
    args = ap.parse_args()

    root = os.path.abspath(args.root)
    cfg, cfg_path = load_cfg(root)
    if not cfg.get("repo") and not cfg.get("aliases"):
        print("未配置上游（upstream.json 缺失且 workflow 无 UPSTREAM_REPO），跳过")
        emit({"repo": "", "branch": "", "status": "none", "renamed": "false", "mode": "none"})
        return 0

    repo_name = args.repo_name or os.path.basename(os.environ.get("GITHUB_REPOSITORY", root).split("/")[-1])

    before = cfg.get("repo")
    info = resolve(cfg)
    if not info.get("repo"):
        print("::warning::上游 %s 无法解析（可能已删除或转私有），保留本地已有内容继续" % before)
        emit({"repo": before, "branch": cfg.get("branch") or "", "status": "missing",
              "renamed": "false", "mode": cfg.get("mode", "mirror")})
        return 0

    new_repo = info["repo"]
    branch = cfg.get("branch") or "auto"
    if branch == "auto" or not branch:
        branch = info["branch"] or "main"
    renamed = bool(before) and before != new_repo

    result = {
        "repo": new_repo,
        "branch": branch,
        "status": "ok",
        "renamed": "true" if renamed else "false",
        "mode": cfg.get("mode", "mirror"),
        "old": before,
        "archived": info["archived"],
        "tried": info.get("tried", []),
    }

    if args.apply:
        cfg["repo"] = new_repo
        cfg["branch"] = cfg.get("branch") or "auto"   # 保留 auto 语义，仅记录
        cfg["resolved_at"] = now_iso()
        cfg["branch_resolved"] = branch
        if renamed:
            aliases = cfg.get("aliases") or []
            if before and before not in aliases:
                aliases.append(before)
            cfg["aliases"] = aliases
            hist = cfg.get("history") or []
            hist.append({"from": before, "to": new_repo, "at": cfg["resolved_at"]})
            cfg["history"] = hist[-12:]
            print("上游改名：%s → %s" % (before, new_repo))
            if repo_name:
                patch_build_downloads(root, repo_name, new_repo)
                patch_description(repo_name, before, new_repo)
        with open(cfg_path, "w", encoding="utf-8") as f:
            json.dump(cfg, f, ensure_ascii=False, indent=2)
            f.write("\n")

    if args.json:
        print(json.dumps(result, ensure_ascii=False))
    else:
        print("上游：%s（分支 %s，%s）" % (new_repo, branch, "已改名跟随" if renamed else "与配置一致"))
        if info.get("tried"):
            print("探测路径：%s" % ", ".join(
                "%s→%s" % (t["name"], t["resolved"] or "失败") for t in info["tried"]))

    emit(result)
    return 0


def emit(result):
    out = os.environ.get("GITHUB_OUTPUT")
    if not out:
        return
    with open(out, "a", encoding="utf-8") as f:
        for k, v in result.items():
            if k in ("tried",):
                continue
            f.write("%s=%s\n" % (k, v))


if __name__ == "__main__":
    sys.exit(main())
