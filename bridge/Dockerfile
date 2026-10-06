# ==============================================================================
# FlutterInspect Bridge Engine Dockerfile
# Follows Blutter's exact official environment recommendations:
# - Debian trixie (GCC 13+, C++20 std::format support)
# - libicu-dev, libcapstone-dev, pkg-config, cmake, ninja-build
# ==============================================================================

FROM debian:trixie-slim

ENV DEBIAN_FRONTEND=noninteractive

# Install official Blutter dependencies per author recommendation
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pyelftools \
    python3-requests \
    git \
    cmake \
    ninja-build \
    build-essential \
    pkg-config \
    libicu-dev \
    libcapstone-dev \
    gcc \
    g++ \
    ca-certificates \
    curl \
    unzip \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Clone latest upstream Blutter repository
RUN git clone https://github.com/worawit/blutter.git /app/blutter

# Copy FlutterInspect companion scripts
COPY blutter2symbols.py /app/blutter2symbols.py
COPY entrypoint.py /app/entrypoint.py

RUN chmod +x /app/entrypoint.py

# Create default volume directory for I/O
RUN mkdir -p /io
WORKDIR /io

ENTRYPOINT ["python3", "/app/entrypoint.py"]
