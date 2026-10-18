FROM eclipse-temurin:21-jdk-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip git python3 autoconf automake libtool pkg-config make gcc g++ nasm autopoint gettext gperf bzip2 xz-utils libtool-bin patch && rm -rf /var/lib/apt/lists/*
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=/opt/android-sdk/cmake/3.22.1/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$PATH
RUN mkdir -p /opt/android-sdk/cmdline-tools && curl -fL https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o /tmp/tools.zip && unzip -q /tmp/tools.zip -d /tmp/android-tools && mv /tmp/android-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest && rm /tmp/tools.zip
RUN yes | sdkmanager --licenses > /dev/null
WORKDIR /workspace
