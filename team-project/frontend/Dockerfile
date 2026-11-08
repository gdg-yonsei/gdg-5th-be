FROM nginx:1.27.4-alpine
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY frontend/index.html frontend/styles.css frontend/app.js frontend/sse.mjs /usr/share/nginx/html/
EXPOSE 80
