FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    curl \
    net-tools \
    && rm -rf /var/lib/apt/lists/*

COPY . .

# DMR Master UDP, OpenBridge UDP, DVSwitch USRP UDP
EXPOSE 62031/udp 62030/udp 12345/udp

CMD ["python", "core_engine.py"]
