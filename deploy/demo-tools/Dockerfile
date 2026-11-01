FROM alpine:3.22.1

ARG TARGETARCH
RUN apk add --no-cache ca-certificates curl tar \
    && case "$TARGETARCH" in \
         amd64) asset_arch=amd64; expected_sha256=4a8128911ccad4e7b481f26635a4cfd1ec064412210526e57ad2c748d356f3b7 ;; \
         arm64) asset_arch=arm64; expected_sha256=6e9dea7ac4f81562add9e1e31206ba6059d6f5929ad4ed924986223282b3aa9c ;; \
         *) echo "Arquitetura RustFS CLI não suportada: $TARGETARCH" >&2; exit 1 ;; \
       esac \
    && curl --fail --location --silent --show-error \
         "https://github.com/rustfs/cli/releases/download/v0.1.36/rustfs-cli-linux-${asset_arch}-v0.1.36.tar.gz" \
         --output /tmp/rc.tar.gz \
    && echo "$expected_sha256  /tmp/rc.tar.gz" | sha256sum -cs \
    && tar -xzf /tmp/rc.tar.gz -C /usr/local/bin rc \
    && rm /tmp/rc.tar.gz \
    && adduser -D -h /home/demo demo \
    && mkdir -p /home/demo/.config/rc \
    && chown -R demo:demo /home/demo
ENV HOME=/home/demo
USER demo
WORKDIR /home/demo
ENTRYPOINT ["/bin/sh", "-ec"]
