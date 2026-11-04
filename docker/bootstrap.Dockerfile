FROM alpine:3.22
RUN apk add --no-cache openssl
COPY docker/bootstrap.sh /bootstrap.sh
ENTRYPOINT ["/bin/sh", "/bootstrap.sh"]
