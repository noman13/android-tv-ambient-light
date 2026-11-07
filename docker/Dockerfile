# Build environment for TV Ambient: JDK 17 + Android SDK 34. The host needs nothing but Docker.
#   docker build -t tv-ambient-build docker/
FROM eclipse-temurin:17-jdk

ARG CMDLINE_TOOLS_VERSION=11076708
ARG COMPILE_SDK=34
ARG BUILD_TOOLS=34.0.0

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/gradle \
    DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip curl \
    && rm -rf /var/lib/apt/lists/*

# sdkmanager expects to live in cmdline-tools/latest.
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && curl -fsSL -o /tmp/cmdline-tools.zip \
       "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" \
    && unzip -q /tmp/cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip

ENV PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager --install "platform-tools" "platforms;android-${COMPILE_SDK}" "build-tools;${BUILD_TOOLS}"

# Gradle's cache lives in a named volume at run time; world-writable so the container can run as any uid.
RUN mkdir -p /gradle && chmod 777 /gradle

WORKDIR /src
