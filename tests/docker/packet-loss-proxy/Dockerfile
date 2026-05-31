FROM alpine:3.20

RUN apk add --no-cache iproute2 socat

COPY packet-loss-proxy/entrypoint.sh /usr/local/bin/pocketshell-packet-loss-proxy
RUN chmod +x /usr/local/bin/pocketshell-packet-loss-proxy

EXPOSE 2229

ENTRYPOINT ["/usr/local/bin/pocketshell-packet-loss-proxy"]
