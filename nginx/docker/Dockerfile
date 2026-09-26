FROM nginx:1.31-alpine
# Patch base-image Alpine OS packages (libxml2, xz-libs, openssl and the like have been flagged before). An upstream
# nginx tag can ship unpatched revisions for a while; apk upgrade pulls the patched ones from the same Alpine branch.
RUN apk upgrade --no-cache
COPY ./nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 50440
