"""Capture only the disposable Android host. Requires Python 3, Pillow and FFmpeg (dev tools)."""
import argparse
import io
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import time

from PIL import Image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--orientation", choices=("portrait", "landscape"), default="portrait")
    parser.add_argument("--existing", action="store_true", help="Capture the already-open generated preview without restarting it")
    parser.add_argument("--method", choices=("video", "screencap"), default="video",
                        help="Video decodes a real display frame; Samsung screencap can return black after secure trials")
    parser.add_argument("--ffmpeg", default=shutil.which("ffmpeg"))
    parser.add_argument("--adb", default=shutil.which("adb") or str(Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe"))
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Use a new output name; existing evidence is never overwritten.")
    video_path = args.output.with_suffix(".h264")
    if args.method == "video" and (not args.ffmpeg or video_path.exists()):
        parser.error("Video capture requires FFmpeg and a new companion .h264 filename.")
    base = [args.adb, "-s", args.serial]

    def adb(*command):
        return subprocess.run(base + list(command), check=True, capture_output=True).stdout.decode("utf-8", "replace")

    power = adb("shell", "dumpsys", "power")
    policy = adb("shell", "dumpsys", "window", "policy")
    if "mWakefulness=Awake" not in power or "mIsShowing=true" in policy:
        parser.error("Unlock the phone before capturing the generated preview.")
    if not args.existing:
        # Start a known generated field. A cold start alone does NOT reliably fix Samsung's
        # screencap failure; the independent video path is the default for visual acceptance.
        adb("shell", "am", "force-stop", "app.odicto.keyboardtrial")
        adb("shell", "am", "start", "-W", "-f", "0x10008000", "-n", "app.odicto.keyboardtrial/.TrialActivity",
            "--es", "geometry_preview", args.orientation, "--ez", "spelling_preview", "true")
        time.sleep(0.7)
    def require_preview():
        activity = adb("shell", "dumpsys", "activity", "activities")
        focused = [line for line in activity.splitlines() if "topResumedActivity" in line]
        if not any("app.odicto.keyboardtrial/.TrialActivity" in line for line in focused):
            parser.error("Refusing capture: the generated fixture is not foreground.")
        windows = adb("shell", "dumpsys", "window", "windows")
        fixture_flags = []
        for block in re.split(r"(?m)^  Window #", windows)[1:]:
            if "app.odicto.keyboardtrial/" not in block.splitlines()[0] or "mViewVisibility=0x0" not in block:
                continue
            flags = re.search(r"(?m)^\s+fl=([0-9a-fA-F]+)\s*$", block)
            if flags:
                fixture_flags.append(int(flags.group(1), 16))
        if len(fixture_flags) != 1 or fixture_flags[0] & 0x2000:
            parser.error("Refusing capture: start a fresh, non-secure generated preview.")

    require_preview()
    video = None
    if args.method == "video":
        video = subprocess.run(base + ["exec-out", "screenrecord", "--output-format=h264", "--time-limit", "2", "-"],
                               capture_output=True, check=True, timeout=15).stdout
        capture = subprocess.run([args.ffmpeg, "-hide_banner", "-loglevel", "error", "-f", "h264", "-i", "pipe:0",
                                  "-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "pipe:1"],
                                 input=video, capture_output=True, check=True, timeout=15).stdout
    else:
        capture = subprocess.run(base + ["exec-out", "screencap", "-p"], capture_output=True, check=True).stdout
    require_preview()
    with Image.open(io.BytesIO(capture)) as frame:
        ranges = frame.convert("RGB").getextrema()
        black = all(low == 0 and high == 0 for low, high in ranges)
        result = {"file": str(args.output), "method": args.method, "size": frame.size,
                  "channelRanges": ranges, "allBlack": black, "fixtureSecure": False}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("xb") as output:
        output.write(capture)
    if video is not None:
        with video_path.open("xb") as output:
            output.write(video)
    print(json.dumps(result))
    if black:
        raise SystemExit("Capture is black; retained as failure evidence, not a verified preview.")


if __name__ == "__main__":
    main()
