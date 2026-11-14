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
    libsndfile1-dev \
    libasound2-dev \
    libsamplerate0-dev \
    libsamplerate0 \
    libudev-dev \
    sox \
    && rm -rf /var/lib/apt/lists/*

# 2. Setup runtime directories
RUN mkdir -p /var/log /var/lib/dvswitch /app/data && \
    touch /var/lib/dvswitch/subscriber_ids.csv

# 3. Build & install mbelib from source for high-fidelity AMBE2+ decoding
RUN git clone https://github.com/szechyjs/mbelib.git /tmp/mbelib && \
    cd /tmp/mbelib && mkdir build && cd build && \
    cmake .. && make && make install && ldconfig && \
    rm -rf /tmp/mbelib

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# 4. Build OpenDMR vocoder library (AMBE+2 encoder & decoder)
RUN if [ -d "/app/opendmr" ]; then \
        cd /app/opendmr && make clean && make && \
        cp libopendmr.so /usr/local/lib/ && ldconfig; \
    fi

# DMR Master UDP (62031), OpenBridge UDP (62030), USRP UDP (32345), Direct Web PTT (32100), Core Control API (5000), Vocoder (2470)
EXPOSE 62031/udp 62030/udp 32345/udp 32100/udp 5000/tcp 2470/udp

CMD ["python", "core_engine.py"]

