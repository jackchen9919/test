FROM eclipse-temurin:8u332-b09-jdk-focal
RUN set -eux; \
    apt update && apt install -y iputils-ping telnet gosu iproute2 procps fontconfig libfreetype6 \
        && rm -rf /var/lib/apt/lists/* \
    && mkdir /app/ /var/www \
    && chown www-data.root /var/www
WORKDIR /app/
COPY docker-entrypoint.sh /app/
ENTRYPOINT ["bash","/app/docker-entrypoint.sh"]
CMD ["start"]
