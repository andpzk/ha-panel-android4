import ipaddress
import json
import subprocess
import threading
import time
from pathlib import Path


def encode(source):
    command = [
        "ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error",
        "-fflags", "nobuffer", "-flags", "low_delay", "-analyzeduration", "500000",
        "-probesize", "131072", "-thread_type", "slice",
        "-rtsp_transport", "tcp", "-timeout", "8000000", "-i", source,
        # Camera RTSP timestamps advance slower than wall time.
        "-map", "0:v:0", "-an", "-vf", "setpts=(RTCTIME-RTCSTART)/(TB*1000000),scale=640:360,fps=10",
        "-c:v", "libx264", "-profile:v", "baseline", "-level:v", "3.0",
        "-pix_fmt", "yuv420p", "-preset", "ultrafast", "-tune", "zerolatency",
        "-threads", "2", "-g", "10", "-sc_threshold", "0", "-crf", "24",
        "-maxrate", "600k", "-bufsize", "600k", "-f", "mpegts", "-flush_packets", "1",
        "udp://127.0.0.1:18800?pkt_size=1316",
    ]
    while True:
        print("Starting the street camera encoder")
        result = subprocess.run(command, stderr=subprocess.DEVNULL)
        print(f"Camera encoder stopped ({result.returncode}), retrying in 4 seconds")
        time.sleep(4)


if __name__ == "__main__":
    source = Path("/config/camera-url").read_text().strip()
    tablet = ipaddress.IPv4Address(Path("/config/tablet-ip").read_text().strip())
    config = Path("/tmp/mediamtx.yml")
    config.write_text(json.dumps({
        "rtspAddress": ":18766",
        "rtspTransports": ["tcp"],
        "rtmp": False, "hls": False, "webrtc": False, "srt": False,
        "authInternalUsers": [{
            "user": "any", "ips": [str(tablet), "127.0.0.1"],
            "permissions": [{"action": "read", "path": "street"}],
        }],
        "paths": {"street": {"source": "udp+mpegts://127.0.0.1:18800"}},
    }))
    with subprocess.Popen(["mediamtx", str(config)]) as rtsp:
        threading.Thread(target=encode, args=(source,), daemon=True).start()
        raise SystemExit(rtsp.wait() or 1)
