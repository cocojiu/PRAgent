FROM golang:1.26.10-bookworm@sha256:fe3da92a5cb2881e4aa75e625cb0b7c50571632fb4cf340cbe53e6ccdcffdd51 AS build

# Keep the official release source immutable; only the Go toolchain and the
# x/net security update (with its required Go modules) differ from v3.7.8.
ADD --checksum=sha256:44c1433b8c5e9638708e62644aa43a746f991206acef5889eaec620a895813b8 \
    https://codeload.github.com/grafana/loki/tar.gz/09e6ce2ff1bdc19763a10265b870c86f51c98655 /tmp/loki.tar.gz
WORKDIR /src/loki
RUN tar -xzf /tmp/loki.tar.gz --strip-components=1 && rm /tmp/loki.tar.gz
ENV CGO_ENABLED=0 GOTOOLCHAIN=local GOMAXPROCS=2
RUN mkdir -p /out && go mod edit -require=golang.org/x/net@v0.60.0 \
      -require=golang.org/x/crypto@v0.57.0 -require=golang.org/x/sys@v0.48.0 \
      -require=golang.org/x/term@v0.46.0 -require=golang.org/x/text@v0.42.0 \
    && go build -mod=mod -p=2 -trimpath -tags netgo \
      -ldflags='-s -w -X github.com/grafana/loki/v3/pkg/util/build.Version=3.7.8-repoguard.2 -X github.com/grafana/loki/v3/pkg/util/build.Revision=09e6ce2ff1bdc19763a10265b870c86f51c98655 -X github.com/grafana/loki/v3/pkg/util/build.Branch=repoguard-security-rebuild -X github.com/grafana/loki/v3/pkg/util/build.BuildUser=repoguard' \
      -o /out/loki ./cmd/loki \
    && go version -m /out/loki > /out/build-info.txt \
    && grep -F 'go1.26.10' /out/build-info.txt \
    && grep -F 'golang.org/x/net' /out/build-info.txt | grep -F 'v0.60.0'

# Preserve the supported runtime filesystem, configuration and non-root user.
# This candidate is validated in CI; production remains on its approved digest.
FROM grafana/loki:3.7.8@sha256:1107dd5274e0ada47e42472b7a7e71f3b2a2fe878878108f3e2f9e51528f0193
COPY --from=build /out/loki /usr/bin/loki
COPY --from=build /out/build-info.txt /usr/share/loki/build-info.txt
COPY --from=build /src/loki/go.mod /src/loki/go.sum /usr/share/loki/source/
COPY --from=build /src/loki/LICENSE /usr/share/licenses/loki/LICENSE
LABEL org.opencontainers.image.source="https://github.com/grafana/loki" \
      org.opencontainers.image.revision="09e6ce2ff1bdc19763a10265b870c86f51c98655" \
      org.opencontainers.image.version="3.7.8-repoguard.2"
