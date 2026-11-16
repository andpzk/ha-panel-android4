FROM bluenviron/mediamtx:1.15.3 AS rtsp
FROM alpine:3.22
RUN apk add --no-cache ffmpeg python3
COPY --from=rtsp /mediamtx /usr/local/bin/mediamtx
COPY relay.py /relay.py
CMD ["python3", "-u", "/relay.py"]
