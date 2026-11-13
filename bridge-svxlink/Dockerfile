# Stage 1: Build modern SvxLink (Reflector Protocol 2.0 with multi-TG support)
FROM debian:bookworm AS builder

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    cmake \
    git \
    ca-certificates \
    libsigc++-2.0-dev \
    libpopt-dev \
    libgcrypt20-dev \
    libjsoncpp-dev \
    libssl-dev \
    libopus-dev \
    libspeex-dev \
    libgsm1-dev \
    libasound2-dev \
    libogg-dev \
    libcurl4-openssl-dev \
    tcl-dev \
    tcl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd -r -g daemon svxlink || true

RUN git clone --depth 1 https://github.com/sm0svx/svxlink.git /src/svxlink

WORKDIR /src/svxlink/src/build

RUN cmake -DCMAKE_INSTALL_PREFIX=/usr \
          -DSYSCONF_INSTALL_DIR=/etc \
          -DLOCAL_STATE_DIR=/var \
          -DUSE_QT=OFF \
          -DWITH_SYSTEMD=OFF \
          .. \
    && make -j$(nproc) \
    && make install DESTDIR=/install_root

# Stage 2: Python SvxLink Bridge Runtime
FROM python:3.11-slim-bookworm

ENV DEBIAN_FRONTEND=noninteractive

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    libsigc++-2.0-0v5 \
    libpopt0 \
    libgcrypt20 \
    libjsoncpp25 \
    libssl3 \
    libopus0 \
    libspeex1 \
    libgsm1 \
    libasound2 \
    libogg0 \
    libcurl4 \
    tcl \
    ca-certificates \
    curl \
    procps \
    && rm -rf /var/lib/apt/lists/*

COPY --from=builder /install_root/usr /usr
RUN ldconfig && (useradd -r -g daemon svxlink || true)

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY ambe_dsp.py .
COPY bridge_svxlink.py .

EXPOSE 12348/udp
EXPOSE 12349/tcp

CMD ["python", "-u", "bridge_svxlink.py"]
