# Web del proyecto (subelo.neuxmatrix.es): web/servidor.js en Node 22, sin compilar nada.
# Las descargas (APK) las deja el workflow en web/descargas/ antes de construir.
FROM node:22-alpine
WORKDIR /srv/web
ENV NODE_ENV=production PORT=8080 DATOS_DIR=/datos TZ=Europe/Madrid
RUN apk add --no-cache tzdata
COPY web/package.json web/package-lock.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY web/servidor.js web/proyecto.json ./
COPY web/descargas ./descargas
RUN mkdir -p /datos && chown node:node /datos
USER node
VOLUME ["/datos"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --retries=3 CMD wget -qO- http://127.0.0.1:8080/salud || exit 1
CMD ["node", "servidor.js"]
