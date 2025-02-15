FROM openjdk:21-jdk-slim

# Install dependencies and nsjail
RUN apt-get update && apt-get upgrade -y && apt-get install -y \
        clang \
        curl \
        libc6 \
        libstdc++6 \
        libprotobuf32 \
        libnl-route-3-200 \
        autoconf \
        bison \
        flex \
        gcc \
        g++ \
        git \
        libprotobuf-dev \
        libnl-route-3-dev \
        libtool \
        make \
        pkg-config \
        protobuf-compiler && \
    git clone --depth 1 https://github.com/google/nsjail.git /usr/local/src/nsjail && \
    cd /usr/local/src/nsjail && \
    make -j && \
    apt-get autoremove -y \
        autoconf \
        bison \
        flex \
        gcc \
        g++ \
        git \
        libprotobuf-dev \
        libnl-route-3-dev \
        libtool \
        make \
        pkg-config \
        protobuf-compiler && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy the built jar file
COPY target/snake.worker*.jar worker.jar

# Default value for environment variables
ENV JWT_SECRET=test-secret
ENV WARN_AUDIT_FAILURE=true
ENV COMPILER_PARAMETER=-std=c++17,-Wall,-Wextra,-Wshadow,-O2
ENV NSJAIL_PARAMETER=-M,o,-R,/lib,-R,/lib64,--disable_proc,--rlimit_cpu,1,--rlimit_as,300,--rlimit_nproc,0,--rlimit_fsize,0,--user,99999,--group,99999,--quiet

# Expose the port the application runs on
EXPOSE ${PORT}

# Command to run the application
ENTRYPOINT [ \
    "java", "-jar", "worker.jar", \
    "--server.port=${PORT}", \
    "--cheese-auth.jwt-secret=${JWT_SECRET}", \
    "--cheese-auth.warn-audit-failure=${WARN_AUDIT_FAILURE}", \
    "--application.compiler-path=/bin/clang++", \
    "--application.compiler-parameter=${COMPILER_PARAMETER}", \
    "--application.data-directory=/app/data", \
    "--application.nsjail-path=/usr/local/src/nsjail/nsjail", \
    "--application.nsjail-parameter=${NSJAIL_PARAMETER}" \
]
