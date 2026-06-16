FROM nginx:1.27-alpine

COPY dist/ /usr/share/nginx/html/
COPY nginx.ip.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
