FROM node:22.23.3-bookworm-slim
WORKDIR /srv/api-service
COPY api-service/package*.json ./
RUN npm ci --omit=dev && npm cache clean --force
COPY --chown=node:node api-service/src ./src
COPY --chown=node:node api-service/web ./web
COPY --chown=node:node api-service/migrations ./migrations
COPY --chown=node:node api-service/operations ./operations
COPY --chown=node:node contracts /srv/contracts
ENV NODE_ENV=production
USER node
EXPOSE 3000
CMD ["node", "src/main.ts"]
