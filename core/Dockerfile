FROM python:3.11-slim

WORKDIR /app

# 1. Base tools, compiler, cmake, git, and qemu emulation
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    cmake \
    git \
    curl \
    gnupg \
    net-tools \
    procps \
    qemu-user-static \
    libsndfile1-dev \
    libasound2-dev \
    sox \
    && rm -rf /var/lib/apt/lists/*

# 2. Add DVSwitch repository for analog-bridge & md380-emu
RUN curl -fsSL http://dvswitch.org/DVSwitch_Repository/dvswitch-keyring.gpg -o /tmp/dvswitch-key.tmp && \
    gpg --dearmor < /tmp/dvswitch-key.tmp > /usr/share/keyrings/dvswitch-keyring.gpg && \
    chmod 644 /usr/share/keyrings/dvswitch-keyring.gpg && \
    rm -f /tmp/dvswitch-key.tmp && \
    echo "deb [signed-by=/usr/share/keyrings/dvswitch-keyring.gpg] http://dvswitch.org/DVSwitch_Repository bookworm hamradio" > /etc/apt/sources.list.d/dvswitch.list && \
    apt-get update && \
    apt-get install -y --no-install-recommends dvswitch-base analog-bridge md380-emu mmdvm-bridge && \
    rm -rf /var/lib/apt/lists/* && \
    ln -sf /opt/Analog_Bridge/Analog_Bridge /usr/local/bin/Analog_Bridge || true && \
    ln -sf /opt/md380-emu/md380-emu /usr/local/bin/md380-emu || true && \
    ln -sf /opt/MMDVM_Bridge/MMDVM_Bridge /usr/local/bin/MMDVM_Bridge || true

# 3. Build & install mbelib from source for high-fidelity AMBE2+ decoding
RUN git clone https://github.com/szechyjs/mbelib.git /tmp/mbelib && \
    cd /tmp/mbelib && mkdir build && cd build && \
    cmake .. && make && make install && ldconfig && \
    rm -rf /tmp/mbelib

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# DMR Master UDP (62031), OpenBridge UDP (62030), USRP UDP (32345), Core Control API (5000), Vocoder (2470)
EXPOSE 62031/udp 62030/udp 32345/udp 5000/tcp 2470/udp

CMD ["python", "core_engine.py"]
