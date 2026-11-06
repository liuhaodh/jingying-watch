# -*- coding: utf-8 -*-
"""生成「净影 · 观看已下载」的本地演示片库。

从 `Downloads` 里挑几段真实的抖音短片，按净影的目录规范铺进
`tools/fixtures/webdav/净影/…`，再补上图集图片、背景音乐和文案.txt。

    python tools/make_fixtures.py

目录规范与「净影 · 电脑版」上传的一致：
    Videos/净影/昵称_抖音号/昵称_文案_时间/视频.mp4
    Videos/净影/昵称_抖音号/昵称_文案_时间/图片_01.jpg
    Videos/净影/昵称_抖音号/昵称_文案_时间/文案.txt
    Music /净影/昵称_抖音号/昵称_文案_时间/音乐.wav
"""
import argparse
import json
import math
import os
import shutil
import struct
import sys
import wave
from datetime import datetime

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover
    Image = None

ROOT = os.path.dirname(os.path.abspath(__file__))
FIXTURE_ROOT = os.path.join(ROOT, "fixtures", "webdav")
DOWNLOADS = os.path.join(os.path.expanduser("~"), "Downloads", "ali")
FOLDER = "净影"

VIDEO_POOL = [
    "%_original.mp4",
    "20260909_133554_original.mp4",
    "cc_original (5).mp4",
    "Angela_original.mp4",
    "chuuuk_original.mp4",
    "eeeat_original.mp4",
    "dokidoki_original.mp4",
    "20260909_183824_original.mp4",
]

WORKS = [
    {
        "author": "小鹿同学", "uid": "lush0918",
        "title": "海边的日落真的治愈",
        "time": "2026-10-30 18:30:12",
        "aweme": "7525082444551310602",
        "caption": "海边的日落真的治愈了所有人🌅 #日落 #旅行 #治愈系风景",
        "kind": "video",
    },
    {
        "author": "小鹿同学", "uid": "lush0918",
        "title": "周末露营vlog",
        "time": "2026-10-29 12:05:40",
        "aweme": "7597000314998344994",
        "caption": "周末露营 vlog，帐篷搭了三遍才成功⛺️ #露营 #周末去哪儿",
        "kind": "video",
    },
    {
        "author": "MimmyKitty", "uid": "MimmyKitty0902",
        "title": "猫咪的日常",
        "time": "2026-10-28 09:01:30",
        "aweme": "7561234567890123456",
        "caption": "猫咪的日常：今天也在认真营业🐱 #猫 #萌宠日常",
        "kind": "video",
    },
    {
        "author": "MimmyKitty", "uid": "MimmyKitty0902",
        "title": "今天也想变可爱",
        "time": "2026-10-27 20:15:00",
        "aweme": "7561234567890123457",
        "caption": "今天也想变可爱一点点✨ #可爱 #日常",
        "kind": "video",
    },
    {
        "author": "阿星", "uid": "94704804628",
        "title": "城市夜景合集",
        "time": "2026-10-26 22:00:18",
        "aweme": "7561234567890123458",
        "caption": "城市夜景合集｜每一盏灯都有自己的故事🌃 #夜景 #城市 #摄影",
        "kind": "album", "images": 3,
    },
    {
        "author": "阿星", "uid": "94704804628",
        "title": "街拍合集",
        "time": "2026-10-25 16:00:00",
        "aweme": "7561234567890123459",
        "caption": "街拍合集，记录路口的三分钟📷 #街拍 #胶片感",
        "kind": "album", "images": 2, "music": True,
    },
]

COLORS = [
    ((32, 40, 72), (120, 88, 168)),
    ((22, 48, 58), (56, 140, 140)),
    ((58, 34, 34), (176, 100, 88)),
    ((26, 32, 30), (96, 128, 104)),
]


def folder_name(work):
    stamp = datetime.strptime(work["time"], "%Y-%m-%d %H:%M:%S").strftime("%Y%m%d_%H%M%S")
    return f"{work['author']}_{work['title']}_{stamp}"


def pick_videos(need):
    if not os.path.isdir(DOWNLOADS):
        return []
    candidates = []
    for name in os.listdir(DOWNLOADS):
        if not name.lower().endswith(".mp4"):
            continue
        path = os.path.join(DOWNLOADS, name)
        size = os.path.getsize(path)
        if 1_500_000 <= size <= 12_000_000:
            candidates.append((name, size))
    candidates.sort(key=lambda pair: pair[0])
    preferred = [name for name in VIDEO_POOL if any(pair[0] == name for pair in candidates)]
    ordered = preferred + [name for name, _ in candidates if name not in preferred]
    return ordered[:need]


def font(size):
    for path in ("C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/msyhbd.ttc",
                 "C:/Windows/Fonts/simhei.ttf"):
        if os.path.isfile(path):
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                continue
    return ImageFont.load_default()


def gradient_image(path, width, height, start, end, lines, index, total):
    image = Image.new("RGB", (width, height), start)
    draw = ImageDraw.Draw(image)
    for y in range(height):
        ratio = y / max(1, height - 1)
        color = tuple(int(start[i] + (end[i] - start[i]) * ratio) for i in range(3))
        draw.line([(0, y), (width, y)], fill=color)
    # 简单的光斑，让封面看起来不像纯色测试图
    for cx, cy, radius, alpha in (
        (width * 0.78, height * 0.22, width * 0.42, 46),
        (width * 0.18, height * 0.78, width * 0.36, 34),
    ):
        overlay = Image.new("RGBA", image.size, (255, 255, 255, 0))
        odraw = ImageDraw.Draw(overlay)
        odraw.ellipse([cx - radius, cy - radius, cx + radius, cy + radius],
                      fill=(255, 255, 255, alpha))
        image = Image.alpha_composite(image.convert("RGBA"), overlay).convert("RGB")
    draw = ImageDraw.Draw(image)
    title_font = font(54)
    meta_font = font(28)
    y = int(height * 0.62)
    for line in lines:
        draw.text((int(width * 0.08), y), line, font=title_font, fill=(255, 255, 255))
        y += 72
    draw.text((int(width * 0.08), int(height * 0.85)),
              f"{index}/{total}  净影 · 观看已下载",
              font=meta_font, fill=(235, 235, 245))
    image.save(path, "JPEG", quality=88)


def make_wav(path, seconds=10.0, rate=44100):
    with wave.open(path, "wb") as handle:
        handle.setnchannels(2)
        handle.setsampwidth(2)
        handle.setframerate(rate)
        frames = bytearray()
        total = int(seconds * rate)
        for index in range(total):
            t = index / rate
            envelope = min(1.0, t / 0.35) * min(1.0, max(0.0, (seconds - t) / 0.5))
            sample = (0.28 * math.sin(2 * math.pi * 220.0 * t)
                      + 0.18 * math.sin(2 * math.pi * 330.0 * t)
                      + 0.10 * math.sin(2 * math.pi * 440.0 * t))
            value = int(max(-1.0, min(1.0, sample * envelope)) * 20000)
            frames += struct.pack("<hh", value, value)
        handle.writeframes(bytes(frames))


def write_caption(path, work):
    text = (
        f"作者：{work['author']}\n"
        f"抖音号：{work['uid']}\n"
        f"发布时间：{work['time']}\n"
        f"作品链接：https://www.douyin.com/video/{work['aweme']}\n"
        f"文案：\n{work['caption']}\n"
    )
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(text)


def make_author_assets(fixture_root):
    """生成 作者资料/authors.json 与头像，模拟电脑版上传的作者资料。"""
    if Image is None:
        return
    profile_root = os.path.join(fixture_root, FOLDER, "作者资料")
    authors_dir = os.path.join(profile_root, "authors")
    os.makedirs(authors_dir, exist_ok=True)
    entries = []
    colors = [((38, 52, 82), (120, 150, 220)), ((60, 38, 60), (220, 120, 170)),
              ((30, 60, 54), (120, 210, 180))]
    seen = set()
    unique_works = []
    for work in WORKS:
        key = (work["author"], work["uid"])
        if key in seen:
            continue
        seen.add(key)
        unique_works.append(work)
    for index, work in enumerate(unique_works):
        author = work["author"]
        uid = work["uid"]
        sec = "MS4wLjABAAAA-demo-" + str(index)
        safe = "".join(c for c in uid if c.isalnum() or c in "-_") or "author"
        folder = os.path.join(authors_dir, safe)
        os.makedirs(folder, exist_ok=True)
        avatar_path = os.path.join(folder, "avatar_demo.jpg")
        image = Image.new("RGB", (256, 256), colors[index % len(colors)][0])
        draw = ImageDraw.Draw(image)
        top, bottom = colors[index % len(colors)]
        for y in range(256):
            ratio = y / 255.0
            color = tuple(int(top[i] + (bottom[i] - top[i]) * ratio) for i in range(3))
            draw.line([(0, y), (256, y)], fill=color)
        draw.text((86, 76), author[0] if author else "净", font=font(96),
                  fill=(255, 255, 255))
        image.save(avatar_path, "JPEG", quality=90)
        fake_local = "C:\\Users\\demo\\downloads\\作者资料\\authors\\" \
                     + safe + "\\avatar_demo.jpg"
        entries.append({
            "sec_uid": sec,
            "nickname": author,
            "unique_id": uid,
            "avatar_url": "",
            "signature": "演示作者",
            "avatar_local": fake_local,
            "avatar_history": [fake_local],
        })
    with open(os.path.join(profile_root, "authors.json"), "w", encoding="utf-8") as handle:
        json.dump(entries, handle, ensure_ascii=False, indent=1)
    print(f"作者资料  {len(entries)} 位作者（含头像）")

def build(clean=False):
    if clean and os.path.isdir(FIXTURE_ROOT):
        shutil.rmtree(FIXTURE_ROOT)
    videos = pick_videos(sum(1 for work in WORKS if work["kind"] == "video"))
    if not videos:
        print("警告：没有在 Downloads/ali 找到可用的 mp4，片库只能生成图集。")
    video_root = os.path.join(FIXTURE_ROOT, FOLDER, "Videos", FOLDER)
    music_root = os.path.join(FIXTURE_ROOT, FOLDER, "Music", FOLDER)
    os.makedirs(video_root, exist_ok=True)
    os.makedirs(music_root, exist_ok=True)

    video_index = 0
    created = 0
    for order, work in enumerate(WORKS):
        author_dir = os.path.join(video_root, f"{work['author']}_{work['uid']}")
        work_dir = os.path.join(author_dir, folder_name(work))
        os.makedirs(work_dir, exist_ok=True)
        write_caption(os.path.join(work_dir, "文案.txt"), work)
        if work["kind"] == "video":
            if video_index < len(videos):
                src = os.path.join(DOWNLOADS, videos[video_index])
                dst = os.path.join(work_dir, "视频.mp4")
                if not os.path.exists(dst) or os.path.getsize(dst) != os.path.getsize(src):
                    shutil.copy2(src, dst)
                print(f"视频  {work['author']} / {work['title']}  <- {videos[video_index]}")
            video_index += 1
        else:
            if Image is None:
                print("缺少 Pillow，跳过图集图片生成")
                continue
            total = work.get("images", 2)
            color = COLORS[order % len(COLORS)]
            for index in range(1, total + 1):
                path = os.path.join(work_dir, f"图片_{index:02d}.jpg")
                gradient_image(path, 900, 1200, color[0], color[1],
                               [work["title"], f"第 {index} 张"], index, total)
            print(f"图集  {work['author']} / {work['title']}  {total} 张")
        if work.get("music"):
            music_dir = os.path.join(music_root, f"{work['author']}_{work['uid']}",
                                     folder_name(work))
            os.makedirs(music_dir, exist_ok=True)
            make_wav(os.path.join(music_dir, "音乐.wav"))
            print(f"音乐  {work['title']}")
        created += 1

    make_author_assets(FIXTURE_ROOT)
    print(f"\n片库：{FIXTURE_ROOT}")
    print(f"作品：{created} 个")
    print("启动测试服务：python tools/mock_dav.py --port 8088")


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--clean", action="store_true", help="先删除旧片库")
    args = parser.parse_args(argv)
    build(clean=args.clean)
    return 0


if __name__ == "__main__":
    sys.exit(main())
