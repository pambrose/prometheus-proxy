FROM nginx:1.29-alpine
# Patch base-image Alpine OS packages flagged by Snyk (libxml2, xz-libs, openssl). The upstream
# nginx:1.29-alpine tag still ships -r0 revisions; apk upgrade pulls the patched revisions from the
# same Alpine branch (e.g. libxml2 2.13.9-r1, xz-libs 5.8.3-r0, libssl3/libcrypto3 3.5.7-r0).
RUN apk upgrade --no-cache
COPY ./nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 50440
