FROM python:3.10-slim

ENV DEBIAN_FRONTEND=noninteractive
ENV PYTHONUNBUFFERED=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    git \
    build-essential \
    python3-dev \
    libffi-dev \
    libssl-dev \
    sqlite3 \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/freedmr

# Clone battle-tested FreeDMR (HBLink3-based DMR Server)
RUN git clone --depth 1 https://github.com/francedmr/FreeDMR.git /opt/freedmr \
    && pip install --no-cache-dir -r requirements.txt \
    && pip install --no-cache-dir dmr_utils3 twisted bitstring bitarray mysql-connector-python resettabletimer setproctitle configparser

# Create alias directory
RUN mkdir -p /opt/freedmr/json

# Expose DMR Hotspot (62031), OpenBridge (62030), and Core Bridge (62035)
EXPOSE 62031/udp 62030/udp 62035/udp

COPY freedmr.cfg /opt/freedmr/freedmr.cfg
COPY rules.py /opt/freedmr/rules.py
COPY entrypoint.sh /opt/freedmr/entrypoint.sh
RUN chmod +x /opt/freedmr/entrypoint.sh

ENTRYPOINT ["/opt/freedmr/entrypoint.sh"]
