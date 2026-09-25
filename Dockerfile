FROM nginx:alpine
COPY privacy-site/index.html /usr/share/nginx/html/index.html
COPY privacy-site/app-ads.txt /usr/share/nginx/html/app-ads.txt
COPY privacy-site/default.conf /etc/nginx/conf.d/default.conf
EXPOSE 8080
